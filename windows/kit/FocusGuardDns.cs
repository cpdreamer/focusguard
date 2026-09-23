// FocusGuard for Windows — blocker-only clone of the phone app.
//
// One executable, compiled at install time with the C# compiler that ships with Windows
// (no download, no .NET install). It is:
//   * a filtering DNS resolver on 127.0.0.1:53 / [::1]:53 applying exactly the phone's rules
//     (lists.txt is generated from BuiltinLists.kt) plus the same 7 downloaded blocklists,
//     forwarding allowed names to Cloudflare for Families over DNS-over-HTTPS;
//   * a watchdog re-applying the system lock every minute (DNS on all adapters, firewall,
//     browser policies, blocked executables) so a change made by any means is undone;
//   * a time-lock: the random administrator password is stored encrypted for SYSTEM only and
//     is written out for the user at the unlock date — never before.
//
// Filter modes (state\mode.txt, chosen at setup):
//   allowonly  default-deny: a name resolves only if it is on allowlist.txt (or was granted through
//              allow.cmd after the waiting period) AND no block rule matches. Every other name, known
//              or unknown, answers 0.0.0.0. The browser gets the same rule (URLBlocklist "*" +
//              URLAllowlist), which also covers raw-IP URLs that never touch DNS.
//   blocklist  the phone's behaviour: everything resolves unless a block rule matches.
//
// Modes:  FocusGuardDns.exe              run as Windows service (installed by setup.ps1)
//         FocusGuardDns.exe console      run in the foreground (debug)
//         FocusGuardDns.exe test <host>  print the decision for a host
//         FocusGuardDns.exe selftest     run the built-in filter tests
//         FocusGuardDns.exe status       show unlock date and list state

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.ServiceProcess;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using Microsoft.Win32;

namespace FocusGuard
{
    // ------------------------------------------------------------------------------------------
    // Rules (mirror of BuiltinLists.kt, loaded from lists.txt)
    // ------------------------------------------------------------------------------------------
    public sealed class Lists
    {
        public HashSet<string> Allow = new HashSet<string>();
        public HashSet<string> Block = new HashSet<string>();
        public List<string> StrongKeywords = new List<string>();
        public List<string> StrongKeywordExceptions = new List<string>();
        public HashSet<string> TokenKeywords = new HashSet<string>();
        public Dictionary<string, string> SafeSearchRewrite = new Dictionary<string, string>();
        public List<string> Sources = new List<string>();
        /// Block-only lists we maintain ourselves (a hole found during a commitment is closed here the
        /// same day). A failed fetch does not veto the daily update, unlike the third-party sources.
        public List<string> OptionalSources = new List<string>();
        public List<string> UrlBlocklist = new List<string>();
        public List<string> UrlAllowlist = new List<string>();

        public static Lists Load(string path)
        {
            var l = new Lists();
            string section = "";
            foreach (var raw in File.ReadAllLines(path))
            {
                var line = raw.Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;
                if (line.StartsWith("[") && line.EndsWith("]")) { section = line.Substring(1, line.Length - 2); continue; }
                switch (section)
                {
                    case "allow": l.Allow.Add(DomainFilter.Normalize(line)); break;
                    case "block": l.Block.Add(DomainFilter.Normalize(line)); break;
                    case "strongKeywords": l.StrongKeywords.Add(line); break;
                    case "strongKeywordExceptions": l.StrongKeywordExceptions.Add(line); break;
                    case "tokenKeywords": l.TokenKeywords.Add(line); break;
                    case "safeSearchRewrite":
                        {
                            var p = line.Split(new[] { ' ' });
                            l.SafeSearchRewrite[DomainFilter.Normalize(p[0])] = DomainFilter.Normalize(p[1]);
                            break;
                        }
                    case "sources": l.Sources.Add(line); break;
                    case "optionalSources": l.OptionalSources.Add(line); break;
                    case "urlBlocklist": l.UrlBlocklist.Add(line); break;
                    case "urlAllowlist": l.UrlAllowlist.Add(line); break;
                }
            }
            if (l.Block.Count < 100 || l.StrongKeywords.Count < 10) throw new InvalidDataException("lists.txt looks truncated");
            return l;
        }
    }

    /// Downloaded blocklist: ~1.2M names kept as 64-bit FNV-1a hashes (same idea as HashedDomainSet.kt).
    public sealed class HashedDomainSet
    {
        readonly HashSet<ulong> set;
        public int Count { get { return set.Count; } }
        HashedDomainSet(HashSet<ulong> s) { set = s; }
        public static HashedDomainSet Empty() { return new HashedDomainSet(new HashSet<ulong>()); }

        public static ulong Hash(string s)
        {
            ulong h = 14695981039346656037UL;
            foreach (char c in s) { h ^= c; h *= 1099511628211UL; }
            return h;
        }
        public bool Contains(string name) { return set.Contains(Hash(name)); }

        public sealed class Builder
        {
            public readonly HashSet<ulong> Set = new HashSet<ulong>();
            public void Add(string host) { Set.Add(Hash(host)); }
            public void AddAll(HashedDomainSet other) { Set.UnionWith(other.set); }
            public HashedDomainSet Build() { return new HashedDomainSet(Set); }
        }

        public void WriteTo(string path)
        {
            using (var w = new BinaryWriter(File.Create(path)))
            {
                w.Write(set.Count);
                foreach (var h in set) w.Write(h);
            }
        }
        public static HashedDomainSet ReadFrom(string path)
        {
            if (!File.Exists(path)) return null;
            try
            {
                using (var r = new BinaryReader(File.OpenRead(path)))
                {
                    int n = r.ReadInt32();
                    var s = new HashSet<ulong>();
                    for (int i = 0; i < n; i++) s.Add(r.ReadUInt64());
                    return new HashedDomainSet(s);
                }
            }
            catch { return null; }
        }

        static readonly HashSet<string> Skip = new HashSet<string> { "localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost" };

        /// hosts-file ("0.0.0.0 host"), plain ("host"), wildcard ("*.host") and adblock ("||host^") formats.
        public static string ParseListLine(string line)
        {
            var s = line.Trim();
            if (s.Length == 0 || s.StartsWith("#") || s.StartsWith("!")) return null;
            int hash = s.IndexOf('#');
            if (hash >= 0) s = s.Substring(0, hash).Trim();
            var parts = s.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return null;
            var host = parts.Length == 1 ? parts[0] : parts[1];
            if (host.StartsWith("*.")) host = host.Substring(2);
            if (host.StartsWith("||")) { host = host.Substring(2); if (host.EndsWith("^")) host = host.Substring(0, host.Length - 1); }
            host = DomainFilter.Normalize(host);
            if (Skip.Contains(host)) return null;
            return DomainFilter.IsValidHost(host) ? host : null;
        }
    }

    /// Allow-only mode: the names that may resolve. allowlist.txt (admin-only, fixed at setup), the
    /// hosts of the blocklist sources (the service must be able to update itself), and the names
    /// granted through the allow.cmd drop box after the waiting period.
    public sealed class AllowOnlyList
    {
        public readonly HashSet<string> Hosts = new HashSet<string>();
        public int FromFile, Granted;

        public static AllowOnlyList Load(string allowlistPath, Lists lists, string grantedPath)
        {
            var a = new AllowOnlyList();
            if (File.Exists(allowlistPath))
                foreach (var raw in File.ReadAllLines(allowlistPath))
                {
                    var h = HashedDomainSet.ParseListLine(raw);
                    if (h != null && a.Hosts.Add(h)) a.FromFile++;
                }
            if (a.FromFile < 10) throw new InvalidDataException("allowlist.txt looks truncated (" + a.FromFile + " hosts)");
            a.AddSources(lists);
            if (grantedPath != null && File.Exists(grantedPath))
                foreach (var raw in File.ReadAllLines(grantedPath))
                {
                    var h = HashedDomainSet.ParseListLine(raw);
                    if (h != null && a.Hosts.Add(h)) a.Granted++;
                }
            return a;
        }
        public static AllowOnlyList SourcesOnly(Lists lists) { var a = new AllowOnlyList(); a.AddSources(lists); return a; }
        void AddSources(Lists lists)
        {
            foreach (var src in lists.Sources.Concat(lists.OptionalSources))
            {
                Uri u;
                if (Uri.TryCreate(src, UriKind.Absolute, out u)) Hosts.Add(DomainFilter.Normalize(u.Host));
            }
        }
    }

    public enum Kind { Allow, Block, Rewrite }
    public struct Decision
    {
        public Kind Kind; public string Detail;
        public override string ToString() { return Kind == Kind.Allow ? "ALLOW" : Kind + "(" + Detail + ")"; }
    }

    /// Same evaluation order as DomainFilter.kt (see its header comment).
    public sealed class DomainFilter
    {
        readonly Lists lists;
        readonly HashedDomainSet downloaded;
        readonly HashSet<string> userBlock;
        readonly AllowOnlyList allowOnly; // null = blocklist mode
        public DomainFilter(Lists lists, HashedDomainSet downloaded) : this(lists, downloaded, null, null) { }
        public DomainFilter(Lists lists, HashedDomainSet downloaded, AllowOnlyList allowOnly, HashSet<string> userBlock)
        {
            this.lists = lists; this.downloaded = downloaded ?? HashedDomainSet.Empty();
            this.allowOnly = allowOnly; this.userBlock = userBlock ?? new HashSet<string>();
        }
        public int DownloadedCount { get { return downloaded.Count; } }
        public bool AllowOnlyMode { get { return allowOnly != null; } }
        public AllowOnlyList AllowOnly { get { return allowOnly; } }

        static readonly Regex Digits = new Regex("[0-9]+", RegexOptions.Compiled);
        static readonly Regex Host = new Regex(@"^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z][a-z0-9-]{0,62}$", RegexOptions.Compiled);
        public static string Normalize(string h) { return h.Trim().TrimEnd('.').ToLowerInvariant(); }
        public static bool IsValidHost(string h) { return Host.IsMatch(h); }

        public Decision Decide(string rawName)
        {
            var name = Normalize(rawName);
            if (name.Length == 0) return new Decision { Kind = Kind.Allow };
            var userHit = MatchesWhich(name, userBlock);
            if (userHit != null) return new Decision { Kind = Kind.Block, Detail = "user:" + userHit };
            var allowHit = MatchesWhich(name, lists.Allow);
            var blockHit = MatchesWhich(name, lists.Block);
            if (blockHit != null && (allowHit == null || blockHit.Length >= allowHit.Length))
                return new Decision { Kind = Kind.Block, Detail = "list:" + blockHit };
            string target;
            if (lists.SafeSearchRewrite.TryGetValue(name, out target)) return new Decision { Kind = Kind.Rewrite, Detail = target };
            if (allowHit != null && (allowOnly == null || MatchesWhich(name, allowOnly.Hosts) != null)) return new Decision { Kind = Kind.Allow };
            var dl = MatchesDownloaded(name);
            if (dl != null) return new Decision { Kind = Kind.Block, Detail = "download:" + dl };
            var kw = KeywordHit(name);
            if (kw != null) return new Decision { Kind = Kind.Block, Detail = "keyword:" + kw };
            if (allowOnly != null && MatchesWhich(name, allowOnly.Hosts) == null)
                return new Decision { Kind = Kind.Block, Detail = "default-deny" };
            return new Decision { Kind = Kind.Allow };
        }

        /// Would this name be blocked even if it were on the allow-only list? (allow.cmd requests
        /// for such names are refused: a granted name never overrides a block rule.)
        public bool BlockedRegardless(string name)
        {
            var d = Decide(name);
            return d.Kind == Kind.Block && d.Detail != "default-deny";
        }

        public static string MatchesWhich(string name, HashSet<string> set)
        {
            if (set.Count == 0) return null;
            var n = name;
            while (true)
            {
                if (set.Contains(n)) return n;
                int dot = n.IndexOf('.');
                if (dot < 0) return null;
                n = n.Substring(dot + 1);
            }
        }
        string MatchesDownloaded(string name)
        {
            if (downloaded.Count == 0) return null;
            var n = name;
            while (true)
            {
                if (downloaded.Contains(n)) return n;
                int dot = n.IndexOf('.');
                if (dot < 0) return null;
                n = n.Substring(dot + 1);
            }
        }
        string KeywordHit(string name)
        {
            foreach (var k in lists.StrongKeywords)
            {
                if (!name.Contains(k)) continue;
                if (lists.StrongKeywordExceptions.Any(e => e.Contains(k) && name.Contains(e))) continue;
                return k;
            }
            foreach (var part in name.Split(new[] { '.', '-', '_' }))
                foreach (var t in Digits.Split(part))
                    if (t.Length > 0 && lists.TokenKeywords.Contains(t)) return t;
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // DNS wire format (mirror of DnsMessage.kt)
    // ------------------------------------------------------------------------------------------
    public sealed class DnsMessage
    {
        public const int TypeA = 1, TypeAAAA = 28;
        public int Id, Flags, QType, QClass; public string QName; public byte[] Raw; int questionEnd;
        public bool IsQuery { get { return (Flags & 0x8000) == 0; } }

        public static DnsMessage Parse(byte[] b, int length)
        {
            if (length < 12) return null;
            var m = new DnsMessage();
            m.Id = (b[0] << 8) | b[1]; m.Flags = (b[2] << 8) | b[3];
            int qd = (b[4] << 8) | b[5];
            if (qd < 1) return null;
            var sb = new StringBuilder(); int pos = 12, hops = 0;
            while (true)
            {
                if (pos >= length) return null;
                int len = b[pos];
                if (len == 0) { pos++; break; }
                if ((len & 0xC0) != 0) return null;
                if (pos + 1 + len > length) return null;
                if (sb.Length > 0) sb.Append('.');
                for (int i = 1; i <= len; i++) sb.Append((char)b[pos + i]);
                pos += 1 + len;
                if (++hops > 128) return null;
            }
            if (pos + 4 > length) return null;
            m.QType = (b[pos] << 8) | b[pos + 1]; m.QClass = (b[pos + 2] << 8) | b[pos + 3];
            pos += 4;
            m.QName = sb.ToString().ToLowerInvariant();
            m.Raw = new byte[length]; Array.Copy(b, m.Raw, length);
            m.questionEnd = pos;
            return m;
        }

        public byte[] BuildServFail()
        {
            var r = BuildResponse(null, null, 0, false);
            r[3] = (byte)((r[3] & 0xF0) | 2);
            return r;
        }

        public byte[] BuildResponse(byte[] answerA, byte[] answerAAAA, int ttl, bool nxDomain)
        {
            byte[] answer = null;
            if (!nxDomain)
            {
                if (QType == TypeA && answerA != null) answer = answerA;
                else if (QType == TypeAAAA && answerAAAA != null) answer = answerAAAA;
            }
            var out_ = new MemoryStream();
            var w = new BinaryWriter(out_);
            Put16(w, Id);
            int flags = 0x8000 | (Flags & 0x7800) | (Flags & 0x0100) | 0x0080 | (nxDomain ? 3 : 0);
            Put16(w, flags); Put16(w, 1); Put16(w, answer != null ? 1 : 0); Put16(w, 0); Put16(w, 0);
            w.Write(Raw, 12, questionEnd - 12);
            if (answer != null)
            {
                Put16(w, 0xC00C); Put16(w, QType); Put16(w, QClass);
                w.Write((byte)(ttl >> 24)); w.Write((byte)(ttl >> 16)); w.Write((byte)(ttl >> 8)); w.Write((byte)ttl);
                Put16(w, answer.Length); w.Write(answer);
            }
            return out_.ToArray();
        }
        static void Put16(BinaryWriter w, int v) { w.Write((byte)(v >> 8)); w.Write((byte)v); }

        public static byte[] BuildQuery(string name, int type, int id)
        {
            var labels = name.TrimEnd('.').Split(new[] { '.' });
            var ms = new MemoryStream(); var w = new BinaryWriter(ms);
            Put16(w, id); Put16(w, 0x0100); Put16(w, 1); Put16(w, 0); Put16(w, 0); Put16(w, 0);
            foreach (var l in labels) { w.Write((byte)l.Length); w.Write(Encoding.ASCII.GetBytes(l)); }
            w.Write((byte)0); Put16(w, type); Put16(w, 1);
            return ms.ToArray();
        }

        public static byte[] FirstAddress(byte[] r, int wantType)
        {
            if (r.Length < 12) return null;
            int qd = (r[4] << 8) | r[5], an = (r[6] << 8) | r[7], p = 12;
            for (int i = 0; i < qd; i++) { p = SkipName(r, p); if (p < 0) return null; p += 4; }
            for (int i = 0; i < an; i++)
            {
                p = SkipName(r, p); if (p < 0 || p + 10 > r.Length) return null;
                int type = (r[p] << 8) | r[p + 1], rdlen = (r[p + 8] << 8) | r[p + 9];
                p += 10;
                if (p + rdlen > r.Length) return null;
                if (type == wantType && (rdlen == 4 || rdlen == 16)) { var a = new byte[rdlen]; Array.Copy(r, p, a, 0, rdlen); return a; }
                p += rdlen;
            }
            return null;
        }
        static int SkipName(byte[] b, int p)
        {
            while (true)
            {
                if (p >= b.Length) return -1;
                int len = b[p];
                if (len == 0) return p + 1;
                if ((len & 0xC0) == 0xC0) return p + 2;
                p += 1 + len;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Resolver: Block -> 0.0.0.0/::, Rewrite -> A/AAAA of the SafeSearch host, Allow -> DoH upstream
    // ------------------------------------------------------------------------------------------
    public sealed class Resolver
    {
        // CleanBrowsing Family first: besides adult content it blocks the proxy / anonymizer / VPN
        // category (web proxies such as trickybird.com, translate.goog, web.archive.org that no static
        // list carries) and forces SafeSearch; remote-browser / cloud-PC services are only partly
        // covered there, hence lists.txt. Cloudflare for Families (malware + adult) is the fallback,
        // used only when CleanBrowsing errors or times out: an empty / NXDOMAIN answer is a block, not
        // a failure. Both reached by IP over HTTPS so that plain port-53 DNS can be firewalled for
        // every program on the machine, including this one.
        public static readonly string[] Upstreams = {
            "https://185.228.168.168/doh/family-filter/", "https://185.228.169.168/doh/family-filter/",
            "https://1.1.1.3/dns-query", "https://1.0.0.3/dns-query",
        };
        const string CleanBrowsingHost = "doh.cleanbrowsing.org";
        static readonly byte[] ZeroV4 = new byte[4], ZeroV6 = new byte[16];

        readonly Func<DomainFilter> filter;
        readonly Dictionary<string, Tuple<byte[], DateTime>> cache = new Dictionary<string, Tuple<byte[], DateTime>>();
        readonly Dictionary<string, Tuple<byte[], DateTime>> rewriteCache = new Dictionary<string, Tuple<byte[], DateTime>>();
        public Action<string, Decision> OnDecision;

        public Resolver(Func<DomainFilter> filter)
        {
            this.filter = filter;
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            ServicePointManager.ServerCertificateValidationCallback = ValidateUpstreamCert;
            ServicePointManager.DefaultConnectionLimit = 64;
        }

        // The upstreams are addressed by IP, so the name check fails by construction; the chain must be
        // valid and the certificate must belong to the resolver operator: *.cleanbrowsing.org, or
        // family.cloudflare-dns.com (which also carries 1.1.1.3 / 1.0.0.3 as IP SANs).
        static bool ValidateUpstreamCert(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errors)
        {
            if (errors == SslPolicyErrors.None) return true;
            if ((errors & ~SslPolicyErrors.RemoteCertificateNameMismatch) != 0) return false;
            var c2 = cert as X509Certificate2 ?? new X509Certificate2(cert);
            var san = c2.Extensions["2.5.29.17"];
            if (san == null) return false;
            var text = san.Format(false);
            var req = sender as HttpWebRequest;
            var host = req != null ? req.RequestUri.Host : "";
            return (host.Length > 0 && text.Contains(host))
                || text.Contains("family.cloudflare-dns.com")
                || text.Contains("*.cleanbrowsing.org") || text.Contains(CleanBrowsingHost);
        }

        public byte[] Resolve(byte[] query, int length)
        {
            var msg = DnsMessage.Parse(query, length);
            if (msg == null || !msg.IsQuery) return null;
            // Firefox disables its own DoH when this canary does not resolve; a 0.0.0.0 answer would
            // not count, NXDOMAIN does.
            if (msg.QName == "use-application-dns.net") return msg.BuildResponse(null, null, 300, true);
            var d = filter().Decide(msg.QName);
            if (OnDecision != null) OnDecision(msg.QName, d);
            switch (d.Kind)
            {
                case Kind.Block: return msg.BuildResponse(ZeroV4, ZeroV6, 300, false);
                case Kind.Rewrite: return Rewrite(msg, d.Detail);
                default: return Forward(msg);
            }
        }

        byte[] Rewrite(DnsMessage msg, string target)
        {
            if (msg.QType != DnsMessage.TypeA && msg.QType != DnsMessage.TypeAAAA) return msg.BuildResponse(null, null, 60, false);
            var addr = ResolveTarget(target, msg.QType);
            if (addr == null) return msg.BuildResponse(null, null, 60, false);
            return msg.QType == DnsMessage.TypeA ? msg.BuildResponse(addr, null, 300, false) : msg.BuildResponse(null, addr, 300, false);
        }
        byte[] ResolveTarget(string target, int type)
        {
            var key = target + "/" + type;
            lock (rewriteCache)
            {
                Tuple<byte[], DateTime> e;
                if (rewriteCache.TryGetValue(key, out e) && e.Item2 > DateTime.UtcNow) return e.Item1;
            }
            var resp = SendUpstream(DnsMessage.BuildQuery(target, type, new Random().Next(65536)));
            if (resp == null) return null;
            var addr = DnsMessage.FirstAddress(resp, type);
            lock (rewriteCache) rewriteCache[key] = Tuple.Create(addr, DateTime.UtcNow.AddMinutes(10));
            return addr;
        }

        byte[] Forward(DnsMessage msg)
        {
            var key = msg.QName + "/" + msg.QType;
            lock (cache)
            {
                Tuple<byte[], DateTime> e;
                if (cache.TryGetValue(key, out e))
                {
                    if (e.Item2 > DateTime.UtcNow) { var c = (byte[])e.Item1.Clone(); c[0] = (byte)(msg.Id >> 8); c[1] = (byte)msg.Id; return c; }
                    cache.Remove(key);
                }
            }
            var resp = SendUpstream(msg.Raw);
            if (resp == null) return msg.BuildServFail();
            lock (cache) { if (cache.Count > 4000) cache.Clear(); cache[key] = Tuple.Create(resp, DateTime.UtcNow.AddSeconds(60)); }
            return resp;
        }

        // HttpWebRequest rather than HttpClient: it honours ServicePointManager's certificate callback on
        // every .NET Framework version (HttpClient's own callback needs 4.7.1+).
        byte[] SendUpstream(byte[] query)
        {
            foreach (var up in Upstreams)
            {
                try
                {
                    var req = (HttpWebRequest)WebRequest.Create(up);
                    if (up.Contains("/doh/")) req.Host = CleanBrowsingHost;
                    req.Method = "POST"; req.ContentType = "application/dns-message"; req.Accept = "application/dns-message";
                    req.Proxy = null; req.Timeout = 4000; req.ReadWriteTimeout = 4000; req.KeepAlive = true;
                    req.ContentLength = query.Length;
                    using (var s = req.GetRequestStream()) s.Write(query, 0, query.Length);
                    using (var resp = (HttpWebResponse)req.GetResponse())
                    using (var rs = resp.GetResponseStream())
                    using (var ms = new MemoryStream())
                    {
                        if ((int)resp.StatusCode != 200) continue;
                        rs.CopyTo(ms);
                        var bytes = ms.ToArray();
                        if (bytes.Length >= 12) return bytes;
                    }
                }
                catch (Exception e) { Log.W("upstream " + up + " failed: " + Root(e).Message); }
            }
            return null;
        }
        static Exception Root(Exception e) { while (e.InnerException != null) e = e.InnerException; return e; }
    }

    /// UDP listener on the loopback addresses; one worker per query.
    public sealed class DnsServer
    {
        readonly Resolver resolver; readonly List<UdpClient> sockets = new List<UdpClient>(); volatile bool running;
        public DnsServer(Resolver r) { resolver = r; }
        public void Start()
        {
            running = true;
            foreach (var ep in new[] { new IPEndPoint(IPAddress.Loopback, 53), new IPEndPoint(IPAddress.IPv6Loopback, 53) })
            {
                try
                {
                    var s = new UdpClient(ep.AddressFamily);
                    s.Client.Bind(ep);
                    sockets.Add(s);
                    var t = new Thread(() => Loop(s)) { IsBackground = true };
                    t.Start();
                    Log.I("listening on " + ep);
                }
                catch (Exception e) { Log.W("cannot bind " + ep + ": " + e.Message); }
            }
            if (sockets.Count == 0) throw new InvalidOperationException("no DNS socket could be bound");
        }
        public void Stop() { running = false; foreach (var s in sockets) s.Close(); }
        void Loop(UdpClient s)
        {
            while (running)
            {
                IPEndPoint from = null;
                byte[] q;
                try { q = s.Receive(ref from); } catch { if (!running) return; continue; }
                var client = from;
                ThreadPool.QueueUserWorkItem(_ =>
                {
                    try { var r = resolver.Resolve(q, q.Length); if (r != null) s.Send(r, r.Length, client); }
                    catch (Exception e) { Log.W("query failed: " + e.Message); }
                });
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Downloaded blocklists (mirror of BlocklistUpdater.kt): daily, all-or-nothing replacement
    // ------------------------------------------------------------------------------------------
    public static class BlocklistUpdater
    {
        const int MinAcceptable = 200000;
        public static bool Update(Lists lists, string targetPath)
        {
            var b = new HashedDomainSet.Builder(); int ok = 0;
            foreach (var src in lists.Sources) if (Fetch(src, b)) ok++;
            var extra = UpdateExtra(lists, Path.Combine(Path.GetDirectoryName(targetPath), "extra.bin"));
            b.AddAll(extra);
            bool have = File.Exists(targetPath);
            if (ok == 0 || b.Set.Count < MinAcceptable || (ok < lists.Sources.Count && have))
            {
                Log.W("blocklist update rejected: sources=" + ok + "/" + lists.Sources.Count + " domains=" + b.Set.Count);
                return false;
            }
            var set = b.Build();
            set.WriteTo(targetPath + ".tmp");
            if (File.Exists(targetPath)) File.Delete(targetPath);
            File.Move(targetPath + ".tmp", targetPath);
            Log.I("blocklist updated: " + set.Count + " domains from " + ok + " sources (+" + extra.Count + " extra)");
            return true;
        }

        /// Optional (our own) sources accumulate in a file that only grows: a name seen once stays
        /// blocked even if later removed upstream, so this channel can tighten but never loosen.
        static HashedDomainSet UpdateExtra(Lists lists, string path)
        {
            var previous = HashedDomainSet.ReadFrom(path) ?? HashedDomainSet.Empty();
            var b = new HashedDomainSet.Builder(); b.AddAll(previous);
            int fetched = 0;
            foreach (var src in lists.OptionalSources) if (Fetch(src, b)) fetched++;
            var merged = b.Build();
            if (fetched > 0 && merged.Count > previous.Count)
            {
                try
                {
                    merged.WriteTo(path + ".tmp");
                    if (File.Exists(path)) File.Delete(path);
                    File.Move(path + ".tmp", path);
                }
                catch (Exception e) { Log.W("extra list not saved: " + e.Message); }
            }
            return merged;
        }

        static bool Fetch(string src, HashedDomainSet.Builder b)
        {
            try
            {
                var req = (HttpWebRequest)WebRequest.Create(src);
                req.UserAgent = "FocusGuard/1.0"; req.Timeout = 20000; req.ReadWriteTimeout = 120000; req.Proxy = null;
                using (var resp = req.GetResponse())
                using (var reader = new StreamReader(resp.GetResponseStream()))
                {
                    string line;
                    while ((line = reader.ReadLine()) != null) { var h = HashedDomainSet.ParseListLine(line); if (h != null) b.Add(h); }
                }
                return true;
            }
            catch (Exception e) { Log.W("source failed: " + src + " " + e.Message); return false; }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Time-lock: the admin password is DPAPI-encrypted for the SYSTEM account and released on date
    // ------------------------------------------------------------------------------------------
    public static class TimeLock
    {
        public static string Dir
        {
            get
            {
                var o = Environment.GetEnvironmentVariable("FOCUSGUARD_DIR"); // tests only
                return string.IsNullOrEmpty(o) ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "FocusGuard") : o;
            }
        }
        public static string StateDir { get { return Path.Combine(Dir, "state"); } }
        public static string UnlockFile { get { return Path.Combine(StateDir, "unlock-date.txt"); } }
        public static string SealedFile { get { return Path.Combine(StateDir, "admin.sealed"); } }
        public static string PlainFile { get { return Path.Combine(StateDir, "admin.plain"); } }
        public static string ReleaseFile { get { return Path.Combine(Dir, "UNLOCKED - admin password.txt"); } }
        /// Drop box a standard user may write to (extend.ps1): a later unlock date is accepted, anything else is not.
        public static string ExtendDir { get { return Path.Combine(Dir, "extend"); } }
        public static string ExtendRequestFile { get { return Path.Combine(ExtendDir, "request.txt"); } }
        public static string ExtendResultFile { get { return Path.Combine(ExtendDir, "result.txt"); } }

        /// Stored as UTC ("2026-10-02T17:12:00Z"): a standard user may change the time zone, not the clock.
        public static DateTime? UnlockDateUtc()
        {
            if (!File.Exists(UnlockFile)) return null;
            DateTime d;
            return DateTime.TryParse(File.ReadAllText(UnlockFile).Trim(), CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out d) ? d : (DateTime?)null;
        }

        /// Files only SYSTEM (and Administrators, of which the user is no longer one) may read.
        static void RestrictToSystem(string path)
        {
            Enforcer.Run("icacls.exe", "\"" + path + "\" /inheritance:r /grant:r *S-1-5-18:F *S-1-5-32-544:F");
        }

        /// setup.ps1 leaves the password in admin.plain (ACL: SYSTEM only); the service seals it with
        /// DPAPI in the SYSTEM user scope on first run so that even a copy of the file is useless.
        public static void SealIfNeeded()
        {
            if (!File.Exists(PlainFile)) return;
            var plain = Encoding.UTF8.GetBytes(File.ReadAllText(PlainFile).Trim());
            var sealed_ = ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser);
            File.WriteAllBytes(SealedFile, sealed_);
            RestrictToSystem(SealedFile);
            Overwrite(PlainFile);
            Log.I("admin password sealed");
        }
        static void Overwrite(string path)
        {
            try { File.WriteAllBytes(path, new byte[256]); File.Delete(path); } catch { }
        }

        /// Tightening-only: the date can be pushed later by anyone, never earlier by anyone. Once the
        /// password has been released it is known, so extending is refused (recommit with setup.ps1).
        public static void ApplyExtendIfRequested()
        {
            if (!File.Exists(ExtendRequestFile)) return;
            string result;
            try
            {
                var text = File.ReadAllText(ExtendRequestFile).Trim();
                var cur = UnlockDateUtc();
                DateTime wanted;
                if (!DateTime.TryParse(text, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out wanted))
                    result = "REFUSED cannot read the date '" + text + "'";
                else if (cur == null)
                    result = "REFUSED no commitment is running";
                else if (File.Exists(ReleaseFile) || DateTime.UtcNow >= cur.Value)
                    result = "REFUSED the previous date is already reached; run setup.cmd -Until ... as " + Enforcer.AdminUser;
                else if (wanted <= cur.Value)
                    result = "REFUSED the date can only move later (current: " + cur.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm") + ")";
                else if (wanted > DateTime.UtcNow.AddDays(120))
                    result = "REFUSED more than 120 days ahead";
                else
                {
                    File.WriteAllText(UnlockFile, wanted.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'"));
                    result = "OK locked until " + wanted.ToLocalTime().ToString("yyyy-MM-dd HH:mm");
                    Log.I("unlock date extended to " + wanted.ToString("u"));
                }
            }
            catch (Exception e) { result = "REFUSED " + e.Message; }
            try { File.Delete(ExtendRequestFile); } catch { }
            try { File.WriteAllText(ExtendResultFile, result); } catch { }
        }

        public static void ReleaseIfDue()
        {
            var due = UnlockDateUtc();
            if (due == null || DateTime.UtcNow < due.Value || File.Exists(ReleaseFile) || !File.Exists(SealedFile)) return;
            var plain = Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(SealedFile), null, DataProtectionScope.CurrentUser));
            File.WriteAllText(ReleaseFile,
                "FocusGuard commitment ended on " + due.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm") + ".\r\n\r\n" +
                "Administrator account: " + Enforcer.AdminUser + "\r\n" +
                "Password: " + plain + "\r\n\r\n" +
                "To remove the blocker: sign out, sign in as " + Enforcer.AdminUser + ", right-click remove.ps1 in " + Dir + " -> Run with PowerShell.\r\n" +
                "To keep it: do nothing. Blocking stays on until you remove it.\r\n");
            Log.I("unlock date reached; admin password released");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Filter mode and the standard-user request drop box (allow.cmd / block.cmd)
    // ------------------------------------------------------------------------------------------
    public static class FilterMode
    {
        public static string File_ { get { return Path.Combine(TimeLock.StateDir, "mode.txt"); } }
        public static bool AllowOnly()
        {
            try { return File.Exists(File_) && File.ReadAllText(File_).Trim().Equals("allowonly", StringComparison.OrdinalIgnoreCase); }
            catch { return false; }
        }
    }

    /// Two one-way channels a standard user may use during the commitment, both tightening-only in
    /// effect:
    ///   block.cmd  a name is blocked immediately and for good (user-block.txt only grows);
    ///   allow.cmd  in allow-only mode, a name not on the allow list becomes reachable after
    ///              AllowDelay, never sooner, never if any block rule matches it, at most MaxPending
    ///              names waiting at a time. The wait is the point: an urge does not last a day.
    public static class Requests
    {
        public static readonly TimeSpan AllowDelay = TimeSpan.FromHours(24);
        public const int MaxPending = 8;
        public static string AllowRequestFile { get { return Path.Combine(TimeLock.ExtendDir, "allow-request.txt"); } }
        public static string AllowResultFile { get { return Path.Combine(TimeLock.ExtendDir, "allow-result.txt"); } }
        public static string BlockRequestFile { get { return Path.Combine(TimeLock.ExtendDir, "block-request.txt"); } }
        public static string BlockResultFile { get { return Path.Combine(TimeLock.ExtendDir, "block-result.txt"); } }
        public static string PendingFile { get { return Path.Combine(TimeLock.StateDir, "allow-pending.txt"); } }
        public static string GrantedFile { get { return Path.Combine(TimeLock.StateDir, "allow-granted.txt"); } }
        public static string UserBlockFile { get { return Path.Combine(TimeLock.StateDir, "user-block.txt"); } }
        const string Stamp = "yyyy-MM-dd'T'HH:mm:ss'Z'";

        public static HashSet<string> LoadUserBlock()
        {
            var s = new HashSet<string>();
            if (!File.Exists(UserBlockFile)) return s;
            foreach (var raw in File.ReadAllLines(UserBlockFile)) { var h = HashedDomainSet.ParseListLine(raw); if (h != null) s.Add(h); }
            return s;
        }

        /// "host readyUtc" per line, sorted by time.
        public static List<KeyValuePair<string, DateTime>> LoadPending()
        {
            var l = new List<KeyValuePair<string, DateTime>>();
            if (!File.Exists(PendingFile)) return l;
            foreach (var raw in File.ReadAllLines(PendingFile))
            {
                var p = raw.Trim().Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                DateTime t;
                if (p.Length == 2 && DateTime.TryParse(p[1], CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out t))
                    l.Add(new KeyValuePair<string, DateTime>(DomainFilter.Normalize(p[0]), t));
            }
            l.Sort((a, b) => a.Value.CompareTo(b.Value));
            return l;
        }
        static void SavePending(List<KeyValuePair<string, DateTime>> l)
        {
            File.WriteAllLines(PendingFile, l.Select(kv => kv.Key + " " + kv.Value.ToString(Stamp)).ToArray());
        }
        static string Local(DateTime utc) { return utc.ToLocalTime().ToString("yyyy-MM-dd HH:mm"); }

        static readonly HashSet<string> NeverBlock = new HashSet<string> { "localhost", "raw.githubusercontent.com", "github.com", "githubusercontent.com", "oisd.nl", "nsfw.oisd.nl" };

        /// Returns true when the rule set changed (the caller rebuilds the filter).
        public static bool Process(DomainFilter filter)
        {
            bool changed = false;
            if (File.Exists(BlockRequestFile)) changed |= ProcessBlock();
            if (File.Exists(AllowRequestFile)) ProcessAllow(filter);
            changed |= Promote();
            return changed;
        }

        static string[] ReadRequest(string path)
        {
            string[] lines;
            try { lines = File.ReadAllLines(path); } catch (Exception e) { lines = new[] { "# " + e.Message }; }
            try { File.Delete(path); } catch { }
            return lines;
        }

        static bool ProcessBlock()
        {
            var result = new List<string>(); var current = LoadUserBlock(); bool changed = false;
            foreach (var raw in ReadRequest(BlockRequestFile))
            {
                var t = raw.Trim(); if (t.Length == 0 || t.StartsWith("#")) continue;
                var h = HashedDomainSet.ParseListLine(t);
                if (h == null) { result.Add("REFUSED '" + t + "' is not a domain name"); continue; }
                if (NeverBlock.Contains(h) || NeverBlock.Any(n => h.EndsWith("." + n)) || h.EndsWith(".localhost")) { result.Add("REFUSED " + h + " carries the blocklist updates"); continue; }
                if (!current.Add(h)) { result.Add("OK " + h + " was already blocked"); continue; }
                result.Add("OK " + h + " blocked (and every subdomain), permanently");
                changed = true;
            }
            if (changed)
            {
                try { File.WriteAllLines(UserBlockFile, current.OrderBy(x => x).ToArray()); Log.I("user block list: " + current.Count + " names"); }
                catch (Exception e) { result.Add("REFUSED cannot save: " + e.Message); changed = false; }
            }
            if (result.Count == 0) result.Add("REFUSED the request was empty");
            try { File.WriteAllLines(BlockResultFile, result.ToArray()); } catch { }
            return changed;
        }

        static void ProcessAllow(DomainFilter filter)
        {
            var result = new List<string>();
            var lines = ReadRequest(AllowRequestFile);
            if (filter == null || !filter.AllowOnlyMode) result.Add("REFUSED the filter is in blocklist mode; there is no allow list to add to");
            else
            {
                var pending = LoadPending(); bool changed = false;
                foreach (var raw in lines)
                {
                    var t = raw.Trim(); if (t.Length == 0 || t.StartsWith("#")) continue;
                    var h = HashedDomainSet.ParseListLine(t);
                    if (h == null) { result.Add("REFUSED '" + t + "' is not a domain name (write e.g. moodle.univ-example.fr, no https://, no path)"); continue; }
                    var d = filter.Decide(h);
                    if (d.Kind != Kind.Block) { result.Add("OK " + h + " is already allowed"); continue; }
                    if (d.Detail != "default-deny") { result.Add("REFUSED " + h + " is blocked by a block rule (" + d.Detail + "); block rules never yield"); continue; }
                    var already = pending.FindIndex(kv => kv.Key == h);
                    if (already >= 0) { result.Add("OK " + h + " is already waiting, allowed from " + Local(pending[already].Value)); continue; }
                    if (pending.Count >= MaxPending) { result.Add("REFUSED " + h + ": " + MaxPending + " names are already waiting; try again after " + Local(pending[0].Value)); continue; }
                    var ready = DateTime.UtcNow + AllowDelay;
                    pending.Add(new KeyValuePair<string, DateTime>(h, ready));
                    result.Add("OK " + h + " (and its subdomains) will be allowed from " + Local(ready) + " - not before");
                    changed = true;
                }
                if (changed)
                {
                    try { SavePending(pending); Log.I("allow requests pending: " + pending.Count); }
                    catch (Exception e) { result.Add("REFUSED cannot save: " + e.Message); }
                }
            }
            if (result.Count == 0) result.Add("REFUSED the request was empty");
            try { File.WriteAllLines(AllowResultFile, result.ToArray()); } catch { }
        }

        static bool Promote()
        {
            var pending = LoadPending();
            if (pending.Count == 0) return false;
            var due = pending.Where(kv => kv.Value <= DateTime.UtcNow).ToList();
            if (due.Count == 0) return false;
            try
            {
                File.AppendAllLines(GrantedFile, due.Select(kv => kv.Key).ToArray());
                SavePending(pending.Where(kv => kv.Value > DateTime.UtcNow).ToList());
                foreach (var kv in due) Log.I("allow request granted: " + kv.Key);
                return true;
            }
            catch (Exception e) { Log.W("promote: " + e.Message); return false; }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Enforcer: re-applies the system lock. Everything here needs admin, which the user no longer is.
    // ------------------------------------------------------------------------------------------
    public static class Enforcer
    {
        public const string AdminUser = "FocusGuardAdmin";
        public static string ExePath { get { return Process.GetCurrentProcess().MainModule.FileName; } }

        // Browsers other than Chrome/Edge (policies do not reach them) and known bypass tools.
        public static readonly string[] BlockedExes = {
            "firefox.exe", "opera.exe", "opera_gx.exe", "brave.exe", "vivaldi.exe", "tor.exe", "torbrowser.exe",
            "chromium.exe", "waterfox.exe", "librewolf.exe", "palemoon.exe", "seamonkey.exe", "yandex.exe", "browser.exe",
            "maxthon.exe", "epic.exe", "avastbrowser.exe", "avgbrowser.exe", "ccleanerbrowser.exe", "duckduckgo.exe",
            "iexplore.exe", "floorp.exe", "thorium.exe", "ungoogled-chromium.exe", "arc.exe", "sidekick.exe",
            "openvpn.exe", "openvpn-gui.exe", "wireguard.exe", "nordvpn.exe", "protonvpn.exe", "expressvpn.exe",
            "surfshark.exe", "cyberghost.exe", "windscribe.exe", "hotspotshield.exe", "psiphon3.exe", "psiphon.exe",
            "ultrasurf.exe", "u.exe", "tunnelbear.exe", "hide.me.exe", "zenmate.exe", "betternet.exe", "urbanvpn.exe",
            "1.1.1.1.exe", "warp-svc.exe", "cloudflare warp.exe", "lantern.exe", "shadowsocks.exe", "v2rayn.exe", "clash.exe",
            "clash for windows.exe", "nekoray.exe", "outlook-vpn.exe", "hola.exe", "simplednscrypt.exe", "dnscrypt-proxy.exe",
            "yogadns.exe", "dnsjumper.exe", "acrylic.exe", "acrylicservice.exe", "technitium.exe", "adguard.exe",
            "qbittorrent.exe", "utorrent.exe", "bittorrent.exe", "deluge.exe", "transmission-qt.exe", "tixati.exe",
            "stremio.exe", "popcorn-time.exe", "popcorn time.exe", "kodi.exe", "steam.exe", "epicgameslauncher.exe",
            "riotclientservices.exe", "leagueclient.exe", "valorant.exe", "battle.net.exe", "origin.exe", "eadesktop.exe",
            "ubisoftconnect.exe", "upc.exe", "minecraftlauncher.exe", "minecraft.exe", "roblox.exe", "robloxplayerbeta.exe",
            "fortnite.exe", "fortniteclient-win64-shipping.exe", "goggalaxy.exe", "rockstarlauncher.exe", "launcher_rockstar.exe",
            "bluestacks.exe", "hd-player.exe", "ldplayer.exe", "dnplayer.exe", "noxplayer.exe", "nox.exe", "memu.exe",
            "gameloop.exe", "androidemulator.exe",
        };

        /// Browser URL patterns (Images/Videos tabs, web proxies) - same as the phone's ChromePolicy.
        public static Lists Lists_;
        public static AllowOnlyList AllowOnly_; // null = blocklist mode

        public static void Apply()
        {
            Try("dns", ApplyDns);
            Try("firewall", ApplyFirewall);
            Try("policies", ApplyPolicies);
            Try("ifeo", ApplyIfeo);
            Try("service-config", ApplyServiceConfig);
            Try("admin-group", CheckAdminGroup);
            Try("recovery", ApplyRecoveryLock);
        }
        static void Try(string what, Action a) { try { a(); } catch (Exception e) { Log.W(what + ": " + e.Message); } }

        // Every adapter -> 127.0.0.1 / ::1. Standard users cannot change adapter DNS or add adapters.
        static void ApplyDns()
        {
            if (!DnsNeedsFix()) return;
            Log.I("adapter DNS changed; re-pointing to the local resolver");
            Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + DnsFixScript.Replace("\"", "\\\"") + "\"");
            Run("ipconfig.exe", "/flushdns");
        }

        public const string DnsFixScript =
            "Get-NetAdapter | ForEach-Object { " +
            "Set-DnsClientServerAddress -InterfaceIndex $_.ifIndex -ServerAddresses @('127.0.0.1','::1') -ErrorAction SilentlyContinue }; " +
            "Set-DnsClientGlobalSetting -UseDevolution $false -ErrorAction SilentlyContinue";

        // Cheap check without spawning PowerShell: every adapter must list only loopback resolvers.
        static bool DnsNeedsFix()
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.NetworkInterfaceType == System.Net.NetworkInformation.NetworkInterfaceType.Loopback) continue;
                foreach (var a in ni.GetIPProperties().DnsAddresses)
                    if (!IPAddress.IsLoopback(a)) return true;
            }
            return false;
        }

        // No program may talk plain DNS / DoT / DoQ to the internet: the only DNS path is this
        // service, which speaks DoH to its own upstreams over 443. Block rules beat allow rules in
        // the Windows firewall, so nothing on the box (not even us) can use port 53 outward.
        public const string PublicDohIps =
            "1.1.1.1,1.0.0.1,1.1.1.2,1.0.0.2,8.8.8.8,8.8.4.4,9.9.9.9,9.9.9.10,9.9.9.11,149.112.112.112,149.112.112.10,149.112.112.11," +
            "94.140.14.14,94.140.15.15,94.140.14.140,94.140.14.141,176.103.130.130,176.103.130.131,208.67.222.222,208.67.220.220,208.67.222.123,208.67.220.123," +
            "76.76.2.0,76.76.10.0,45.90.28.0,45.90.30.0,185.228.168.9,185.228.169.9,185.228.168.10,185.228.169.11," +
            "77.88.8.8,77.88.8.1,77.88.8.88,77.88.8.2,77.88.8.7,77.88.8.3,194.242.2.2-194.242.2.9,193.110.81.0,185.253.5.0,193.110.81.9,185.253.5.9,101.101.101.101," +
            "2606:4700:4700::1111,2606:4700:4700::1001,2606:4700:4700::1112,2606:4700:4700::1002,2001:4860:4860::8888,2001:4860:4860::8844," +
            "2620:fe::fe,2620:fe::9,2620:fe::10,2620:fe::fe:10,2620:fe::11,2620:fe::fe:11,2a10:50c0::ad1:ff,2a10:50c0::ad2:ff,2a10:50c0::bad1:ff,2a10:50c0::bad2:ff," +
            "2620:119:35::35,2620:119:53::53,2606:1a40::,2606:1a40:1::,2a07:a8c0::,2a07:a8c1::,2a0d:2a00:1::,2a0d:2a00:2::,2a0d:2a00:1::1,2a0d:2a00:2::1";

        static void ApplyFirewall()
        {
            var rules = new[] {
                new[] { "FocusGuard block DNS udp", "protocol=UDP remoteport=53 dir=out action=block" },
                new[] { "FocusGuard block DNS tcp", "protocol=TCP remoteport=53 dir=out action=block" },
                new[] { "FocusGuard block DoT tcp", "protocol=TCP remoteport=853 dir=out action=block" },
                new[] { "FocusGuard block DoQ udp", "protocol=UDP remoteport=853 dir=out action=block" },
                // Well-known public DoH resolvers by IP (a renamed browser could be pointed at them). Our
                // own upstreams (CleanBrowsing 185.228.168/169.168, Cloudflare 1.1.1.3 / 1.0.0.3) are deliberately not in the list.
                new[] { "FocusGuard block public DoH", "protocol=TCP remoteport=443 dir=out action=block remoteip=" + PublicDohIps },
                new[] { "FocusGuard block public DoH udp", "protocol=UDP remoteport=443 dir=out action=block remoteip=" + PublicDohIps },
                // loopback DNS to the local resolver stays possible: firewall never filters 127.0.0.1
            };
            foreach (var r in rules)
            {
                int code;
                Run("netsh", "advfirewall firewall show rule name=\"" + r[0] + "\"", out code);
                if (code == 0) continue; // netsh exits 1 when no rule matches, whatever the display language
                Run("netsh", "advfirewall firewall add rule name=\"" + r[0] + "\" " + r[1] + " enable=yes profile=any");
            }
            Run("netsh", "advfirewall set allprofiles state on");
            Run("netsh", "advfirewall set allprofiles firewallpolicy blockinbound,allowoutbound");
        }

        /// Allow-only mode in the browser: URLBlocklist "*" (every URL, including raw-IP ones that
        /// never reach DNS) with URLAllowlist = allowed hosts + the browser's own pages. Chrome
        /// resolves conflicts by specificity, so the existing path-level blocks (Images tab, web
        /// caches) still win over an allowed host. Both lists are capped at 1000 entries by Chrome.
        public static void UrlPolicyAllowOnly(Lists lists, AllowOnlyList allowOnly, HashSet<string> userBlock, out List<string> block, out List<string> allow)
        {
            block = new List<string>(lists.UrlBlocklist);
            block.Add("*");
            foreach (var p in new[] { "chrome://flags", "chrome://net-internals", "edge://flags", "edge://net-internals" }) if (!block.Contains(p)) block.Add(p);
            foreach (var h in userBlock) if (!block.Contains(h)) block.Add(h);
            var seen = new HashSet<string>(lists.UrlAllowlist.Select(u => u.Split('/')[0].ToLowerInvariant()));
            allow = new List<string>(lists.UrlAllowlist) { "chrome://*", "edge://*", "chrome-extension://*", "chrome-untrusted://*", "file://*", "devtools://*" };
            foreach (var h in allowOnly.Hosts.Concat(lists.SafeSearchRewrite.Keys).OrderBy(x => x))
                if (seen.Add(h) && DomainFilter.MatchesWhich(h, lists.Block) == null) allow.Add(h);
            if (allow.Count > 1000) { Log.W("URLAllowlist has " + allow.Count + " entries; Chrome reads the first 1000"); }
        }

        // Chrome + Edge machine policies (HKLM\Software\Policies is admin-only; both browsers honour
        // it on every Windows edition, Home included). Firefox is blocked as an executable instead.
        static void ApplyPolicies()
        {
            foreach (var root in new[] { @"SOFTWARE\Policies\Google\Chrome", @"SOFTWARE\Policies\Microsoft\Edge", @"SOFTWARE\Policies\Chromium", @"SOFTWARE\Policies\BraveSoftware\Brave" })
            {
                SetReg(root, "ForceGoogleSafeSearch", 1);
                SetReg(root, "ForceYouTubeRestrict", 2);
                SetReg(root, "IncognitoModeAvailability", 1);
                SetReg(root, "BrowserGuestModeEnabled", 0);
                SetReg(root, "DnsOverHttpsMode", "off");
                SetReg(root, "BuiltInDnsClientEnabled", 0);
                SetReg(root, "ProxyMode", "direct");
                SetReg(root, "AllowDeletingBrowserHistory", 0);
                SetReg(root, "SavingBrowserHistoryDisabled", 0);
                SetReg(root, "DeveloperToolsAvailability", 2);
                SetReg(root, "BrowserSignin", 1);
                SetReg(root, "QuicAllowed", 0);
                SetReg(root, "RemoteAccessHostAllowRemoteAccessConnections", 0);
                SetReg(root, "ExtensionInstallBlocklist", new Dictionary<string, string> { { "1", "*" } });
                SetReg(root, "ExtensionInstallAllowlist", new Dictionary<string, string> {
                    { "1", "ghbmnnjooekpmoecnnnilnnbdlolhkhi" }, // Google Docs Offline
                    { "2", "cjpalhdlnbpafiamejdnhcphjbkeiagm" }, // uBlock Origin
                    { "3", "kbfnbcaeplbcioakkpcpgfkobkghlhen" }, // Grammarly
                    { "4", "nngceckbapebfimnlniiiahkandclblb" }, // Bitwarden
                });
            }
            // Edge-specific: SafeSearch on Bing, no InPrivate, no sidebar/discover feed, no Edge games
            if (Lists_ != null)
            {
                var urlBlock = Lists_.UrlBlocklist; var urlAllow = Lists_.UrlAllowlist;
                if (AllowOnly_ != null) UrlPolicyAllowOnly(Lists_, AllowOnly_, Requests.LoadUserBlock(), out urlBlock, out urlAllow);
                foreach (var root in new[] {
                    @"SOFTWARE\Policies\Google\Chrome",
                    @"SOFTWARE\Policies\Microsoft\Edge",
                    @"SOFTWARE\Policies\Chromium",
                    @"SOFTWARE\Policies\BraveSoftware\Brave"
                })
                {
                    SetReg(root, "URLBlocklist", Numbered(urlBlock));
                    SetReg(root, "URLAllowlist", Numbered(urlAllow));
                }
            }
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "InPrivateModeAvailability", 1);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "HubsSidebarEnabled", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "NewTabPageContentEnabled", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "AllowGamesMenu", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "EdgeShoppingAssistantEnabled", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "ShowMicrosoftRewards", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Edge", "EdgeCollectionsEnabled", 0);
            // The system proxy is machine-wide (not per user) and locked: a user-level proxy would
            // route traffic around the local DNS filter.
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\CurrentVersion\Internet Settings", "ProxySettingsPerUser", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Internet Explorer\Control Panel", "Proxy", 1);
            SetReg(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Internet Settings", "ProxyEnable", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\CurrentVersion\Internet Settings", "ProxyEnable", 0);
            // Windows Store: no Xbox / games, no Windows Spotlight feed on the lock screen
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\CloudContent", "DisableWindowsConsumerFeatures", 1);
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\CloudContent", "DisableWindowsSpotlightFeatures", 1);
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\Windows Feeds", "EnableFeeds", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Dsh", "AllowNewsAndInterests", 0);
            SetReg(@"SOFTWARE\Policies\Microsoft\Windows\GameDVR", "AllowGameDVR", 0);
            // no Windows Sandbox / WSL / Hyper-V escape hatches for standard users: features need admin anyway
        }

        // Image File Execution Options: Windows starts the named "debugger" instead of the program.
        // Works on Home; renaming the exe defeats it (documented). The DNS filter still applies to
        // a renamed browser; this only removes the incognito/DoH/proxy freedom of non-policy browsers.
        static void ApplyIfeo()
        {
            var blocker = Path.Combine(TimeLock.Dir, "blocked.exe");
            foreach (var exe in BlockedExes)
                SetReg(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options\" + exe, "Debugger", blocker);
        }

        static void ApplyServiceConfig()
        {
            Run("sc.exe", "failure FocusGuardDns reset= 0 actions= restart/2000/restart/2000/restart/2000");
            Run("sc.exe", "config FocusGuardDns start= auto");
            // Only SYSTEM and Administrators may touch the service; interactive users may query only.
            Run("sc.exe", "sdset FocusGuardDns D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;IU)(A;;CCLCSWLOCRRC;;;SU)");
            // safe mode (with networking) starts only the services listed here: keep filtering there too
            foreach (var mode in new[] { "Minimal", "Network" })
                SetReg(@"SYSTEM\CurrentControlSet\Control\SafeBoot\" + mode + @"\FocusGuardDns", "", "Service");
        }

        // Nobody but FocusGuardAdmin may be in Administrators (a member whose password the user knows
        // is an exit). The group and the built-in Administrator are addressed by SID so this works on
        // a French/any-language Windows. FocusGuardAdmin itself is kept present and enabled: it is the
        // only account that can ever remove the lock.
        public const string AdminGroupScript =
            "$g = Get-LocalGroup -SID 'S-1-5-32-544'; " +
            "$me = Get-LocalUser -Name '" + AdminUser + "' -ErrorAction SilentlyContinue; " +
            "if ($me) { if (-not $me.Enabled) { Enable-LocalUser -Name '" + AdminUser + "' }; " +
            "  if (-not (Get-LocalGroupMember -Group $g -Member '" + AdminUser + "' -ErrorAction SilentlyContinue)) { Add-LocalGroupMember -Group $g -Member '" + AdminUser + "' } }; " +
            "Get-LocalGroupMember -Group $g | ForEach-Object { " +
            "  $n = $_.Name.Split('\\')[-1]; " +
            "  if ($n -ieq '" + AdminUser + "') { return }; " +
            "  if ($_.SID.Value -like '*-500') { $u = Get-LocalUser -SID $_.SID -ErrorAction SilentlyContinue; if ($u -and $u.Enabled) { Disable-LocalUser -SID $_.SID; Write-Output ('disabled ' + $n) }; return }; " +
            "  Remove-LocalGroupMember -Group $g -Member $_.SID -ErrorAction SilentlyContinue; Write-Output ('removed ' + $n) }";

        static void CheckAdminGroup()
        {
            // Never strip the Administrators group while the only account that may remove the lock
            // is missing: that would leave the machine with no administrator at all.
            var probe = Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"(Get-LocalUser -Name '" + AdminUser + "' -ErrorAction SilentlyContinue) -ne $null\"") ?? "";
            if (!probe.Trim().Equals("True", StringComparison.OrdinalIgnoreCase)) { Log.W("admin group: " + AdminUser + " does not exist; leaving Administrators untouched"); return; }
            var out_ = Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command \"" + AdminGroupScript.Replace("\"", "\\\"") + "\"") ?? "";
            foreach (var line in out_.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries)) Log.W("admin group: " + line.Trim());
        }

        // No Windows Recovery Environment from the login screen (Shift+Restart -> Reset this PC) and
        // no F8/automatic-repair menu: reinstalling from a USB stick stays the one honest exit.
        // Cheap, idempotent, run once per service start.
        static bool recoveryDone;
        static void ApplyRecoveryLock()
        {
            if (recoveryDone) return;
            recoveryDone = true;
            Run("reagentc.exe", "/disable");
            Run("bcdedit.exe", "/set {default} recoveryenabled No");
            Run("bcdedit.exe", "/set {default} bootstatuspolicy ignoreallfailures");
            Run("bcdedit.exe", "/set {bootmgr} displaybootmenu No");
        }

        static Dictionary<string, string> Numbered(List<string> items)
        {
            var d = new Dictionary<string, string>();
            for (int i = 0; i < items.Count; i++) d[(i + 1).ToString()] = items[i];
            return d;
        }

        public static void SetReg(string path, string name, object value)
        {
            using (var k = Registry.LocalMachine.CreateSubKey(path))
            {
                var dict = value as Dictionary<string, string>;
                if (dict != null)
                {
                    using (var sub = k.CreateSubKey(name))
                    {
                        foreach (var kv in dict) if (!kv.Value.Equals(sub.GetValue(kv.Key))) sub.SetValue(kv.Key, kv.Value, RegistryValueKind.String);
                        foreach (var existing in sub.GetValueNames()) if (!dict.ContainsKey(existing)) sub.DeleteValue(existing);
                    }
                    return;
                }
                var cur = k.GetValue(name);
                if (value is int) { if (!(cur is int) || (int)cur != (int)value) k.SetValue(name, (int)value, RegistryValueKind.DWord); }
                else if (!value.Equals(cur)) k.SetValue(name, value, RegistryValueKind.String);
            }
        }

        public static string Run(string file, string args) { int code; return Run(file, args, out code); }
        public static string Run(string file, string args, out int exitCode)
        {
            exitCode = -1;
            try
            {
                var p = new Process();
                p.StartInfo = new ProcessStartInfo(file, args) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
                p.Start();
                var o = p.StandardOutput.ReadToEnd(); p.StandardError.ReadToEnd();
                if (p.WaitForExit(60000)) exitCode = p.ExitCode;
                return o;
            }
            catch (Exception e) { Log.W(file + " " + args + ": " + e.Message); return null; }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Service wiring
    // ------------------------------------------------------------------------------------------
    public static class Log
    {
        static readonly object gate = new object();
        public static bool Console_;
        static string File_ { get { return Path.Combine(TimeLock.StateDir, "focusguard.log"); } }
        public static void I(string m) { Write("I " + m); }
        public static void W(string m) { Write("W " + m); }
        static void Write(string m)
        {
            var line = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + m;
            if (Console_) Console.WriteLine(line);
            lock (gate)
            {
                try
                {
                    Directory.CreateDirectory(TimeLock.StateDir);
                    if (File.Exists(File_) && new FileInfo(File_).Length > 2000000) File.Delete(File_);
                    File.AppendAllText(File_, line + "\r\n");
                }
                catch { }
            }
        }
    }

    public sealed class Engine
    {
        Lists lists; volatile DomainFilter filter; DnsServer server; Timer enforceTimer, updateTimer, unlockTimer;
        readonly string listsPath = Path.Combine(TimeLock.Dir, "lists.txt");
        readonly string downloadedPath = Path.Combine(TimeLock.StateDir, "downloaded.bin");
        readonly object reload = new object();
        public bool Enforce = true;

        public static string AllowlistPath { get { return Path.Combine(TimeLock.Dir, "allowlist.txt"); } }

        /// The filter for the installed rules: lists.txt + downloaded lists + (allow-only mode) the
        /// allow list with its granted names + the user's own blocks. Used by the service and the CLI.
        public static DomainFilter BuildFilter(Lists lists, string downloadedPath)
        {
            AllowOnlyList allowOnly = null;
            if (FilterMode.AllowOnly())
            {
                // fail closed: an unreadable allow list denies everything but the list sources, the
                // service stays up (extend/allow/block/release keep working) and logs the reason
                try { allowOnly = AllowOnlyList.Load(AllowlistPath, lists, Requests.GrantedFile); }
                catch (Exception e) { Log.W("allowlist.txt: " + e.Message + " - denying everything"); allowOnly = AllowOnlyList.SourcesOnly(lists); }
            }
            return new DomainFilter(lists, HashedDomainSet.ReadFrom(downloadedPath), allowOnly, Requests.LoadUserBlock());
        }

        void Rebuild()
        {
            lock (reload)
            {
                var f = BuildFilter(lists, downloadedPath);
                Enforcer.AllowOnly_ = f.AllowOnly;
                filter = f;
                Log.I("rules: mode=" + (f.AllowOnlyMode ? "allow-only (" + f.AllowOnly.Hosts.Count + " names, " + f.AllowOnly.Granted + " granted)" : "blocklist") +
                      " block=" + lists.Block.Count + " allow=" + lists.Allow.Count + " downloaded=" + f.DownloadedCount);
            }
        }

        public void Start()
        {
            Directory.CreateDirectory(TimeLock.StateDir);
            lists = Lists.Load(listsPath);
            Enforcer.Lists_ = lists;
            Rebuild();
            var resolver = new Resolver(() => filter);
            server = new DnsServer(resolver);
            server.Start();
            if (Enforce)
            {
                TimeLock.SealIfNeeded();
                enforceTimer = new Timer(_ => Enforcer.Apply(), null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(60));
            }
            unlockTimer = new Timer(_ =>
            {
                if (Enforce)
                {
                    try { TimeLock.ApplyExtendIfRequested(); TimeLock.ReleaseIfDue(); }
                    catch (Exception e) { Log.W("unlock: " + e.Message); }
                }
                try { if (Requests.Process(filter)) { Rebuild(); if (Enforce) Enforcer.Apply(); } }
                catch (Exception e) { Log.W("requests: " + e.Message); }
            }, null, TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(15));
            var firstUpdate = File.Exists(downloadedPath) && (DateTime.UtcNow - File.GetLastWriteTimeUtc(downloadedPath)) < TimeSpan.FromHours(20)
                ? TimeSpan.FromHours(1) : TimeSpan.FromSeconds(30);
            updateTimer = new Timer(_ => UpdateLists(), null, firstUpdate, TimeSpan.FromHours(6));
        }
        void UpdateLists()
        {
            bool ok = false;
            try
            {
                ok = BlocklistUpdater.Update(lists, downloadedPath);
                if (ok) Rebuild();
            }
            catch (Exception e) { Log.W("update: " + e.Message); }
            // nothing downloaded yet (setup ran offline, captive portal...): retry every 10 min, not tomorrow
            if (!ok && !File.Exists(downloadedPath) && updateTimer != null)
                updateTimer.Change(TimeSpan.FromMinutes(10), TimeSpan.FromHours(6));
        }
        public void Stop()
        {
            if (server != null) server.Stop();
            foreach (var t in new[] { enforceTimer, updateTimer, unlockTimer }) if (t != null) t.Dispose();
        }
    }

    public sealed class GuardService : ServiceBase
    {
        readonly Engine engine = new Engine();
        public GuardService() { ServiceName = "FocusGuardDns"; CanStop = true; CanShutdown = true; }
        protected override void OnStart(string[] args) { engine.Start(); }
        protected override void OnStop() { engine.Stop(); }
        protected override void OnShutdown() { engine.Stop(); }
    }

    public static class Program
    {
        public static int Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
            if (args.Length == 0) { ServiceBase.Run(new GuardService()); return 0; }
            Log.Console_ = true;
            switch (args[0])
            {
                case "console":
                    {
                        var e = new Engine { Enforce = !args.Contains("--no-enforce") };
                        e.Start();
                        Console.WriteLine("running; Ctrl+C to stop");
                        Thread.Sleep(Timeout.Infinite);
                        return 0;
                    }
                case "test":
                    {
                        var installed = Path.Combine(TimeLock.Dir, "lists.txt");
                        var beside = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "lists.txt");
                        var lists = Lists.Load(File.Exists(installed) ? installed : beside);
                        var f = Engine.BuildFilter(lists, Path.Combine(TimeLock.StateDir, "downloaded.bin"));
                        for (int i = 1; i < args.Length; i++) Console.WriteLine(args[i] + " -> " + f.Decide(args[i]));
                        return 0;
                    }
                case "selftest":
                    {
                        var lp = args.Length > 1 ? args[1] : Path.Combine(TimeLock.Dir, "lists.txt");
                        var ap = args.Length > 2 ? args[2] : Path.Combine(Path.GetDirectoryName(lp), "allowlist.txt");
                        return SelfTest.Run(lp, ap);
                    }
                case "status":
                    {
                        var due = TimeLock.UnlockDateUtc();
                        Console.WriteLine("unlock date: " + (due == null ? "(none)" : due.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm")) +
                                          (due != null && DateTime.UtcNow >= due.Value ? "  (reached)" : ""));
                        Console.WriteLine("password released: " + File.Exists(TimeLock.ReleaseFile));
                        var dl = HashedDomainSet.ReadFrom(Path.Combine(TimeLock.StateDir, "downloaded.bin"));
                        Console.WriteLine("downloaded blocklist: " + (dl == null ? "not yet" : dl.Count + " domains"));
                        if (FilterMode.AllowOnly())
                        {
                            AllowOnlyList a = null;
                            try { a = AllowOnlyList.Load(Engine.AllowlistPath, Lists.Load(Path.Combine(TimeLock.Dir, "lists.txt")), Requests.GrantedFile); } catch (Exception e) { Console.WriteLine("allow list: ERROR " + e.Message); }
                            Console.WriteLine("filter mode: allow-only" + (a == null ? "" : " (" + a.FromFile + " names in allowlist.txt, " + a.Granted + " granted by request)"));
                            var pending = Requests.LoadPending();
                            Console.WriteLine("allow requests waiting: " + pending.Count + " (max " + Requests.MaxPending + ", each waits " + Requests.AllowDelay.TotalHours + " h)");
                            foreach (var kv in pending) Console.WriteLine("   " + kv.Key + "  from " + kv.Value.ToLocalTime().ToString("yyyy-MM-dd HH:mm"));
                        }
                        else Console.WriteLine("filter mode: blocklist");
                        Console.WriteLine("blocked on request (block.cmd): " + Requests.LoadUserBlock().Count + " names");
                        return 0;
                    }
                case "enforce-once":
                    Enforcer.Lists_ = Lists.Load(Path.Combine(TimeLock.Dir, "lists.txt"));
                    Enforcer.AllowOnly_ = Engine.BuildFilter(Enforcer.Lists_, Path.Combine(TimeLock.StateDir, "downloaded.bin")).AllowOnly;
                    Enforcer.Apply();
                    return 0;
                case "update-lists":
                    {
                        var lists = Lists.Load(Path.Combine(TimeLock.Dir, "lists.txt"));
                        Directory.CreateDirectory(TimeLock.StateDir);
                        return BlocklistUpdater.Update(lists, Path.Combine(TimeLock.StateDir, "downloaded.bin")) ? 0 : 1;
                    }
                default: Console.Error.WriteLine("usage: FocusGuardDns.exe [console|test host...|selftest|status]"); return 2;
            }
        }
    }

    /// The same assertions as DomainFilterTest.kt, so the Windows clone is provably the same filter.
    public static class SelfTest
    {
        public static int Run(string listsPath, string allowlistPath)
        {
            var lists = Lists.Load(listsPath);
            var f = new DomainFilter(lists, HashedDomainSet.Empty());
            int failed = 0;
            Action<string, bool> expect = (host, blocked) =>
            {
                var d = f.Decide(host);
                bool isBlocked = d.Kind != Kind.Allow;
                if (isBlocked != blocked) { failed++; Console.WriteLine("FAIL " + host + " -> " + d + " (expected " + (blocked ? "block" : "allow") + ")"); }
            };
            failed += AllowOnlyTests(lists, allowlistPath);
            foreach (var h in new[] {
                "youtube.com", "m.youtube.com", "www.tiktok.com", "instagram.com", "x.com", "twitter.com", "reddit.com",
                "netflix.com", "twitch.tv", "pornhub.com", "xvideos.com", "onlyfans.com", "omegle.com", "janitorai.com",
                "images.google.com", "encrypted-tbn0.gstatic.com", "th.bing.com", "external-content.duckduckgo.com",
                "gettyimages.com", "www.gettyimages.fr", "gettyimages.co.jp", "istockphoto.com", "shutterstock.com",
                "unsplash.com", "pexels.com", "flickr.com", "wallhaven.cc", "fr.pinterest.com", "forja.ma", "www.forja.ma",
                "shahid.mbc.net", "weyyak.com", "koora.live", "tf1.fr", "mycanal.fr", "1xbet.com", "fmovies.example",
                "sex.example", "some-movies.example", "casino.example", "yacine-tv.example",
            }) expect(h, true);
            foreach (var h in new[] {
                "wikipedia.org", "fr.wikipedia.org", "docs.google.com", "drive.google.com", "mail.google.com", "www.gstatic.com",
                "fonts.gstatic.com", "chat.openai.com", "chatgpt.com", "gemini.google.com", "claude.ai", "whatsapp.com",
                "web.whatsapp.com", "discord.com", "slack.com", "chess.com", "lichess.org", "photos.google.com", "photomath.net",
                "essex.ac.uk", "campus.fr", "sextant.example", "stream.microsoft.com", "codeforces.com", "github.com",
                "shahidul-university.example", "openphoto.example", "microsoft.com", "login.microsoftonline.com",
            }) expect(h, false);
            foreach (var kv in new[] { new[] { "www.google.com", "forcesafesearch.google.com" }, new[] { "google.fr", "forcesafesearch.google.com" }, new[] { "www.bing.com", "strict.bing.com" }, new[] { "duckduckgo.com", "safe.duckduckgo.com" } })
            {
                var d = f.Decide(kv[0]);
                if (d.Kind != Kind.Rewrite || d.Detail != kv[1]) { failed++; Console.WriteLine("FAIL " + kv[0] + " -> " + d + " (expected rewrite " + kv[1] + ")"); }
            }
            // list-line parsing and the hashed set round trip
            var b = new HashedDomainSet.Builder();
            foreach (var line in new[] { "0.0.0.0 bad.example", "*.wild.example", "||adblock.example^", "plain.example", "# comment", "127.0.0.1 localhost" })
            { var h = HashedDomainSet.ParseListLine(line); if (h != null) b.Add(h); }
            var set = b.Build();
            if (set.Count != 4 || !set.Contains("bad.example") || !set.Contains("wild.example") || !set.Contains("adblock.example") || set.Contains("localhost")) { failed++; Console.WriteLine("FAIL list parsing: " + set.Count); }
            var f2 = new DomainFilter(Lists.Load(listsPath), set);
            if (f2.Decide("cdn.wild.example").Kind != Kind.Block) { failed++; Console.WriteLine("FAIL downloaded parent match"); }
            // DNS wire format round trip
            var q = DnsMessage.BuildQuery("example.com", DnsMessage.TypeA, 0x1234);
            var m = DnsMessage.Parse(q, q.Length);
            if (m == null || m.QName != "example.com" || m.QType != 1 || m.Id != 0x1234 || !m.IsQuery) { failed++; Console.WriteLine("FAIL dns parse"); }
            else
            {
                var r = m.BuildResponse(new byte[] { 1, 2, 3, 4 }, null, 300, false);
                if ((r[2] & 0x80) == 0 || DnsMessage.FirstAddress(r, 1) == null || DnsMessage.FirstAddress(r, 1)[3] != 4) { failed++; Console.WriteLine("FAIL dns response"); }
            }
            Console.WriteLine(failed == 0 ? "selftest OK" : "selftest FAILED: " + failed);
            return failed == 0 ? 0 : 1;
        }

        /// Allow-only mode against the kit's allowlist.txt: block rules still win, the allow list
        /// (with parent matching) lets school/infrastructure names through, everything else is denied.
        static int AllowOnlyTests(Lists lists, string allowlistPath)
        {
            int failed = 0;
            if (!File.Exists(allowlistPath)) { Console.WriteLine("FAIL allowlist.txt missing: " + allowlistPath); return 1; }
            var a = AllowOnlyList.Load(allowlistPath, lists, null);
            var dl = new HashedDomainSet.Builder(); dl.Add("downloaded-bad.example");
            var f = new DomainFilter(lists, dl.Build(), a, new HashSet<string> { "userblocked.example", "docs.google.com" });
            Action<string, string> expect = (host, want) =>
            {
                var d = f.Decide(host);
                var got = d.Kind == Kind.Block ? (d.Detail == "default-deny" ? "deny" : "block") : d.Kind.ToString().ToLowerInvariant();
                if (got != want) { failed++; Console.WriteLine("FAIL allow-only " + host + " -> " + d + " (expected " + want + ")"); }
            };
            foreach (var h in new[] {
                // unknown names, present or future: dead without anyone having to know them
                "newbird.example", "some-new-proxy.example", "example.com", "example.org", "neverheardof.xyz", "a.b.c.d.example",
                "vercel.app", "my-proxy.vercel.app", "github.io", "pages.dev", "workers.dev", "netlify.app", "cloudfront.net",
                "ngrok.io", "repl.co", "glitch.me", "herokuapp.com", "onrender.com", "fly.dev", "koyeb.app",
            }) expect(h, "deny");
            foreach (var h in new[] {
                // block rules (static, downloaded, keyword, user) win even for names under the allow list
                "youtube.com", "pornhub.com", "images.google.com", "encrypted-tbn0.gstatic.com", "news.google.com",
                "downloaded-bad.example", "cdn.downloaded-bad.example", "userblocked.example", "www.userblocked.example", "docs.google.com",
                "trickybird.com", "hyperbeam.com", "croxyproxy.com", "sex.example",
            }) expect(h, "block");
            foreach (var h in new[] {
                // school, chat, infrastructure, and the service's own list sources
                "drive.google.com", "mail.google.com", "accounts.google.com", "fr.wikipedia.org", "en.m.wikipedia.org", "upload.wikimedia.org",
                "web.whatsapp.com", "chatgpt.com", "chat.openai.com", "claude.ai", "doc-solus.fr", "links.duckduckgo.com",
                "login.microsoftonline.com", "outlook.office.com", "onedrive.live.com", "www.msftconnecttest.com", "download.windowsupdate.com",
                "dl.google.com", "update.googleapis.com", "fonts.gstatic.com", "fonts.googleapis.com", "lh3.googleusercontent.com",
                "raw.githubusercontent.com", "nsfw.oisd.nl", "github.com", "codeforces.com", "ankiweb.net", "overleaf.com",
                "stackoverflow.com", "wolframalpha.com", "geogebra.org", "leetcode.com",
            }) expect(h, "allow");
            // the phone's [allow] exemptions (Discord, chess, Spotify...) do not open anything here: allowlist.txt alone decides
            foreach (var h in new[] { "discord.com", "gateway.discord.gg", "lichess.org", "chess.com", "spotify.com", "slack.com" }) expect(h, "deny");
            foreach (var h in new[] { "www.google.com", "google.fr", "www.bing.com", "duckduckgo.com" }) expect(h, "rewrite");
            // allow.cmd refuses names a block rule matches, accepts only the default-denied ones
            if (!f.BlockedRegardless("pornhub.com") || !f.BlockedRegardless("userblocked.example") || f.BlockedRegardless("neverheardof.xyz") || f.BlockedRegardless("drive.google.com"))
            { failed++; Console.WriteLine("FAIL BlockedRegardless"); }
            // browser policy mirror
            List<string> ub, ua;
            Enforcer.UrlPolicyAllowOnly(lists, a, new HashSet<string> { "userblocked.example" }, out ub, out ua);
            if (!ub.Contains("*") || !ub.Contains("userblocked.example") || !ub.Contains("google.com/search?tbm=isch")) { failed++; Console.WriteLine("FAIL url blocklist"); }
            if (!ua.Contains("wikipedia.org") || !ua.Contains("drive.google.com") || !ua.Contains("whatsapp.com") || !ua.Contains("google.com") || !ua.Contains("chrome://*") || !ua.Contains("file://*"))
            { failed++; Console.WriteLine("FAIL url allowlist"); }
            if (ua.Contains("youtube.com") || ua.Contains("images.google.com") || ua.Contains("discord.com") || ua.Count > 1000) { failed++; Console.WriteLine("FAIL url allowlist leak/size " + ua.Count); }
            // blocklist mode is untouched
            var plain = new DomainFilter(lists, HashedDomainSet.Empty());
            if (plain.Decide("neverheardof.xyz").Kind != Kind.Allow || plain.AllowOnlyMode) { failed++; Console.WriteLine("FAIL blocklist mode"); }
            Console.WriteLine("allow-only: " + a.FromFile + " names in allowlist.txt, " + a.Hosts.Count + " with list sources; browser allowlist " + ua.Count + " entries");
            return failed;
        }
    }
}
