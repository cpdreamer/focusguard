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
// Changing the rules during a commitment (v4). Every change is a one-line directive, read from the
// local drop box (extend\fg-request.txt, written by fg.cmd / block.cmd / allow.cmd as a standard
// user) or from the remote config file ([config] URL in lists.txt, fetched every 15 min). Whoever
// writes the line, the same rule applies:
//   tightening takes effect at once, any hour, and is permanent until the unlock date:
//     block <host>      block list only grows (beats every allow, no directive removes an entry)
//     unallow <host>    drop a name from the allow list (can be re-asked later, through the window)
//     browsers off      Chrome/Edge show "blocked" (IFEO) and their URL policy allows nothing
//     blockapp <x.exe>  an executable is blocked by name (IFEO), no directive unblocks it
//     cancel <host>     a waiting allow request is dropped
//   loosening is queued and lands at the START of the first daily window (state\window.txt,
//   e.g. 06:00-08:00 in the fixed offset written at setup) that begins at least 8 h after the
//   request; never sooner, never if a block rule matches, never for an application:
//     allow <host>      a default-denied name becomes reachable
//     browsers on       undo browsers off
//   Applications are frozen for the whole commitment: what runs today runs, nothing new runs
//   (Software Restriction Policies: standard users execute only from Program Files / Windows / the
//   per-user program folders present at setup), and there is no "allowapp" directive at all.
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
        /// Remote directive file (see the header): fetched every 15 min, applied line by line, once.
        public string ConfigUrl;

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
                    case "config": if (l.ConfigUrl == null && line.StartsWith("https://")) l.ConfigUrl = line; break;
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
    /// hosts of the blocklist/config sources (the service must be able to update itself), and the
    /// names granted through allow requests after the waiting period - minus the names removed
    /// again with "unallow" (a removal also takes every subdomain with it, whatever file it came from).
    public sealed class AllowOnlyList
    {
        public readonly HashSet<string> Hosts = new HashSet<string>();
        public readonly HashSet<string> SourceHosts = new HashSet<string>();
        public int FromFile, Granted, Removed;

        public static AllowOnlyList Load(string allowlistPath, Lists lists, string grantedPath) { return Load(allowlistPath, lists, grantedPath, null); }
        public static AllowOnlyList Load(string allowlistPath, Lists lists, string grantedPath, string unallowedPath)
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
            if (unallowedPath != null && File.Exists(unallowedPath)) a.Remove(ReadHosts(unallowedPath));
            return a;
        }
        public static AllowOnlyList SourcesOnly(Lists lists) { var a = new AllowOnlyList(); a.AddSources(lists); return a; }

        public static HashSet<string> ReadHosts(string path)
        {
            var s = new HashSet<string>();
            if (!File.Exists(path)) return s;
            foreach (var raw in File.ReadAllLines(path)) { var h = HashedDomainSet.ParseListLine(raw); if (h != null) s.Add(h); }
            return s;
        }

        /// Tightening: every host equal to or under a removed name leaves the list, except the
        /// list/config sources (removing those would silently stop the daily updates).
        public void Remove(HashSet<string> unallowed)
        {
            if (unallowed.Count == 0) return;
            var gone = Hosts.Where(h => !SourceHosts.Contains(h) && DomainFilter.MatchesWhich(h, unallowed) != null).ToList();
            foreach (var h in gone) Hosts.Remove(h);
            Removed = gone.Count;
        }

        void AddSources(Lists lists)
        {
            var srcs = lists.Sources.Concat(lists.OptionalSources).ToList();
            if (lists.ConfigUrl != null) srcs.Add(lists.ConfigUrl);
            foreach (var src in srcs)
            {
                Uri u;
                if (!Uri.TryCreate(src, UriKind.Absolute, out u)) continue;
                var h = DomainFilter.Normalize(u.Host);
                Hosts.Add(h); SourceHosts.Add(h);
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
        readonly HashSet<string> unallowed;
        readonly AllowOnlyList allowOnly; // null = blocklist mode
        public DomainFilter(Lists lists, HashedDomainSet downloaded) : this(lists, downloaded, null, null) { }
        public DomainFilter(Lists lists, HashedDomainSet downloaded, AllowOnlyList allowOnly, HashSet<string> userBlock) : this(lists, downloaded, allowOnly, userBlock, null) { }
        public DomainFilter(Lists lists, HashedDomainSet downloaded, AllowOnlyList allowOnly, HashSet<string> userBlock, HashSet<string> unallowed)
        {
            this.lists = lists; this.downloaded = downloaded ?? HashedDomainSet.Empty();
            this.allowOnly = allowOnly; this.userBlock = userBlock ?? new HashSet<string>();
            this.unallowed = unallowed ?? new HashSet<string>();
        }
        public HashSet<string> UserBlock { get { return userBlock; } }
        public HashSet<string> Unallowed { get { return unallowed; } }
        public int DownloadedCount { get { return downloaded.Count; } }
        public bool AllowOnlyMode { get { return allowOnly != null; } }
        public AllowOnlyList AllowOnly { get { return allowOnly; } }

        static readonly Regex Digits = new Regex("[0-9]+", RegexOptions.Compiled);
        static readonly Regex Host = new Regex(@"^(?=.{1,253}$)([a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?\.)+[a-z][a-z0-9-]{0,62}$", RegexOptions.Compiled);
        public static string Normalize(string h) { return h.Trim().TrimEnd('.').ToLowerInvariant(); }
        public static bool IsValidHost(string h) { return Host.IsMatch(h); }

        public Decision Decide(string rawName) { return Decide(rawName, false); }
        public Decision Decide(string rawName, bool ignoreUnallowed)
        {
            var name = Normalize(rawName);
            if (name.Length == 0) return new Decision { Kind = Kind.Allow };
            var userHit = MatchesWhich(name, userBlock);
            if (userHit != null) return new Decision { Kind = Kind.Block, Detail = "user:" + userHit };
            var allowHit = MatchesWhich(name, lists.Allow);
            var blockHit = MatchesWhich(name, lists.Block);
            if (blockHit != null && (allowHit == null || blockHit.Length >= allowHit.Length))
                return new Decision { Kind = Kind.Block, Detail = "list:" + blockHit };
            // "unallow" of a name whose parent stays allowed: a block until re-allowed through the window
            var unHit = allowOnly != null && !ignoreUnallowed ? MatchesWhich(name, unallowed) : null;
            if (unHit != null) return new Decision { Kind = Kind.Block, Detail = "unallowed:" + unHit };
            // SafeSearch rewrite only for an engine the allow list reaches anyway (google.com is there for
            // account/Drive/Gmail); other engines are default-denied like any other unlisted name
            string target;
            if (lists.SafeSearchRewrite.TryGetValue(name, out target) && (allowOnly == null || MatchesWhich(name, allowOnly.Hosts) != null))
                return new Decision { Kind = Kind.Rewrite, Detail = target };
            if (allowHit != null && (allowOnly == null || MatchesWhich(name, allowOnly.Hosts) != null)) return new Decision { Kind = Kind.Allow };
            var dl = MatchesDownloaded(name);
            if (dl != null) return new Decision { Kind = Kind.Block, Detail = "download:" + dl };
            var kw = KeywordHit(name);
            if (kw != null) return new Decision { Kind = Kind.Block, Detail = "keyword:" + kw };
            if (allowOnly != null && MatchesWhich(name, allowOnly.Hosts) == null)
                return new Decision { Kind = Kind.Block, Detail = "default-deny" };
            return new Decision { Kind = Kind.Allow };
        }

        /// Would this name be blocked even if it were on the allow-only list? (allow requests for
        /// such names are refused: a granted name never overrides a block rule.) An "unallowed"
        /// name is not in that class: re-allowing it is exactly what the window is for.
        public bool BlockedRegardless(string name)
        {
            var d = Decide(name, true);
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
    // Filter mode, the daily window, and the directive channels (drop box + remote config)
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

    /// The daily window in which loosening lands: "HH:mm-HH:mm +HH:mm" (start, end, fixed UTC
    /// offset - the offset is frozen at setup because a standard user may change the time zone).
    /// A loosening asked at T lands at the first window start >= T + MinAge, so nothing asked in
    /// the evening lands before the next morning, and nothing asked at 03:00 lands the same morning.
    /// No window file (or an unreadable one) = no loosening at all: fail closed.
    public sealed class Window
    {
        public static readonly TimeSpan MinAge = TimeSpan.FromHours(8);
        public int StartMin, EndMin; public TimeSpan Offset;
        public static string File_ { get { return Path.Combine(TimeLock.StateDir, "window.txt"); } }

        public static Window Parse(string text)
        {
            var m = Regex.Match(text.Trim(), @"^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})\s+([+-])(\d{2}):(\d{2})$");
            if (!m.Success) throw new FormatException("window must read like 06:00-08:00 +01:00, got '" + text.Trim() + "'");
            var w = new Window
            {
                StartMin = int.Parse(m.Groups[1].Value) * 60 + int.Parse(m.Groups[2].Value),
                EndMin = int.Parse(m.Groups[3].Value) * 60 + int.Parse(m.Groups[4].Value),
                Offset = new TimeSpan(int.Parse(m.Groups[6].Value), int.Parse(m.Groups[7].Value), 0),
            };
            if (m.Groups[5].Value == "-") w.Offset = -w.Offset;
            if (w.StartMin >= 24 * 60 || w.EndMin > 24 * 60 || w.StartMin >= w.EndMin) throw new FormatException("window start must be before its end, same day");
            if (w.EndMin - w.StartMin > 4 * 60) throw new FormatException("window longer than 4 h");
            return w;
        }
        public static Window Load()
        {
            try { return File.Exists(File_) ? Parse(File.ReadAllText(File_)) : null; }
            catch (Exception e) { Log.W("window.txt: " + e.Message + " - no loosening possible"); return null; }
        }

        DateTime ToLocal(DateTime utc) { return DateTime.SpecifyKind(utc + Offset, DateTimeKind.Unspecified); }
        public bool IsOpen(DateTime utc)
        {
            var l = ToLocal(utc); int m = l.Hour * 60 + l.Minute;
            return m >= StartMin && m < EndMin;
        }
        /// First window start at or after `utc`.
        public DateTime NextStart(DateTime utc)
        {
            var l = ToLocal(utc);
            var s = l.Date.AddMinutes(StartMin);
            if (s < l) s = s.AddDays(1);
            return DateTime.SpecifyKind(s - Offset, DateTimeKind.Utc);
        }
        /// When a loosening asked at `requestedUtc` takes effect.
        public DateTime Lands(DateTime requestedUtc) { return NextStart(requestedUtc + MinAge); }
        public string Local(DateTime utc) { return ToLocal(utc).ToString("yyyy-MM-dd HH:mm"); }
        public override string ToString()
        {
            return (StartMin / 60).ToString("00") + ":" + (StartMin % 60).ToString("00") + "-" + (EndMin / 60).ToString("00") + ":" + (EndMin % 60).ToString("00") +
                   " (UTC" + (Offset < TimeSpan.Zero ? "-" : "+") + Offset.Duration().ToString("hh\\:mm") + ")";
        }
    }

    /// The directive channels. Two local drop boxes a standard user may write to (block.cmd /
    /// allow.cmd keep their one-name-per-line files; fg.cmd writes full directives) and the remote
    /// config file. Every line goes through Directives.Apply, which decides tightening-now versus
    /// loosening-in-window; nothing else ever changes the rules during a commitment.
    public static class Requests
    {
        public const int MaxPending = 8;
        public static string AllowRequestFile { get { return Path.Combine(TimeLock.ExtendDir, "allow-request.txt"); } }
        public static string AllowResultFile { get { return Path.Combine(TimeLock.ExtendDir, "allow-result.txt"); } }
        public static string BlockRequestFile { get { return Path.Combine(TimeLock.ExtendDir, "block-request.txt"); } }
        public static string BlockResultFile { get { return Path.Combine(TimeLock.ExtendDir, "block-result.txt"); } }
        public static string DirectiveRequestFile { get { return Path.Combine(TimeLock.ExtendDir, "fg-request.txt"); } }
        public static string DirectiveResultFile { get { return Path.Combine(TimeLock.ExtendDir, "fg-result.txt"); } }
        public static string PendingFile { get { return Path.Combine(TimeLock.StateDir, "allow-pending.txt"); } }
        public static string GrantedFile { get { return Path.Combine(TimeLock.StateDir, "allow-granted.txt"); } }
        public static string UserBlockFile { get { return Path.Combine(TimeLock.StateDir, "user-block.txt"); } }
        public static string UnallowedFile { get { return Path.Combine(TimeLock.StateDir, "unallowed.txt"); } }
        public static string BrowsersOffFile { get { return Path.Combine(TimeLock.StateDir, "browsers-off.txt"); } }
        public static string AppBlockFile { get { return Path.Combine(TimeLock.StateDir, "app-block.txt"); } }
        public static string ConfigSeenFile { get { return Path.Combine(TimeLock.StateDir, "config-seen.txt"); } }
        /// Pending-list key for "browsers on" (not a host name, cannot collide with one).
        public const string BrowsersKey = "@browsers";
        const string Stamp = "yyyy-MM-dd'T'HH:mm:ss'Z'";
        public static readonly object Gate = new object();

        public static HashSet<string> LoadUserBlock() { return AllowOnlyList.ReadHosts(UserBlockFile); }
        public static HashSet<string> LoadUnallowed() { return AllowOnlyList.ReadHosts(UnallowedFile); }
        public static bool BrowsersOff() { return File.Exists(BrowsersOffFile); }
        public static List<string> LoadAppBlock()
        {
            var l = new List<string>();
            if (!File.Exists(AppBlockFile)) return l;
            foreach (var raw in File.ReadAllLines(AppBlockFile)) { var e = Directives.ExeName(raw); if (e != null && !l.Contains(e)) l.Add(e); }
            return l;
        }

        /// "key landsUtc" per line, sorted by time.
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
        public static void SavePending(List<KeyValuePair<string, DateTime>> l)
        {
            File.WriteAllLines(PendingFile, l.Select(kv => kv.Key + " " + kv.Value.ToString(Stamp)).ToArray());
        }
        public static void SaveHosts(string path, HashSet<string> hosts) { File.WriteAllLines(path, hosts.OrderBy(x => x).ToArray()); }

        /// Returns true when the rule set changed (the caller rebuilds the filter and re-enforces).
        public static bool Process(DomainFilter filter)
        {
            lock (Gate)
            {
                bool changed = false;
                if (File.Exists(BlockRequestFile)) changed |= Batch(filter, BlockRequestFile, BlockResultFile, "block");
                if (File.Exists(AllowRequestFile)) changed |= Batch(filter, AllowRequestFile, AllowResultFile, "allow");
                if (File.Exists(DirectiveRequestFile)) changed |= Batch(filter, DirectiveRequestFile, DirectiveResultFile, null);
                changed |= Promote(Window.Load());
                return changed;
            }
        }

        static bool Batch(DomainFilter filter, string requestPath, string resultPath, string verb)
        {
            string[] lines;
            try { lines = File.ReadAllLines(requestPath); } catch (Exception e) { lines = new[] { "# " + e.Message }; }
            try { File.Delete(requestPath); } catch { }
            var result = new List<string>(); bool changed = false;
            foreach (var raw in lines)
            {
                var t = raw.Trim(); if (t.Length == 0 || t.StartsWith("#")) continue;
                bool c;
                result.Add(Directives.Apply(verb == null ? t : verb + " " + t, filter, Window.Load(), DateTime.UtcNow, out c));
                changed |= c;
            }
            if (result.Count == 0) result.Add("REFUSED the request was empty");
            try { File.WriteAllLines(resultPath, result.ToArray()); } catch { }
            return changed;
        }

        /// Remote config lines not applied yet (by exact text). Caller holds Gate.
        public static bool ApplyRemote(IEnumerable<string> lines, DomainFilter filter)
        {
            var seen = new HashSet<string>(File.Exists(ConfigSeenFile) ? File.ReadAllLines(ConfigSeenFile) : new string[0]);
            bool changed = false; var newlySeen = new List<string>();
            foreach (var raw in lines)
            {
                var t = raw.Trim(); if (t.Length == 0 || t.StartsWith("#") || seen.Contains(t)) continue;
                bool c;
                var r = Directives.Apply(t, filter, Window.Load(), DateTime.UtcNow, out c);
                Log.I("config: " + t + " -> " + r);
                changed |= c; newlySeen.Add(t);
            }
            if (newlySeen.Count > 0) try { File.AppendAllLines(ConfigSeenFile, newlySeen.ToArray()); } catch (Exception e) { Log.W("config-seen: " + e.Message); }
            return changed;
        }

        /// Loosening lands: every pending entry whose time has come is applied. Caller holds Gate.
        public static bool Promote(Window w)
        {
            var pending = LoadPending();
            if (pending.Count == 0) return false;
            var now = DateTime.UtcNow;
            var due = pending.Where(kv => kv.Value <= now).ToList();
            if (due.Count == 0) return false;
            try
            {
                var hosts = due.Where(kv => kv.Key != BrowsersKey).Select(kv => kv.Key).ToList();
                if (hosts.Count > 0)
                {
                    File.AppendAllLines(GrantedFile, hosts.ToArray());
                    var un = LoadUnallowed();
                    if (un.RemoveWhere(hosts.Contains) > 0) SaveHosts(UnallowedFile, un);
                }
                if (due.Any(kv => kv.Key == BrowsersKey) && File.Exists(BrowsersOffFile)) File.Delete(BrowsersOffFile);
                SavePending(pending.Where(kv => kv.Value > now).ToList());
                foreach (var kv in due) Log.I("window: " + (kv.Key == BrowsersKey ? "browsers on" : "allow " + kv.Key) + " takes effect");
                return true;
            }
            catch (Exception e) { Log.W("promote: " + e.Message); return false; }
        }
    }

    /// One directive = one line. Pure decision + state-file update; the caller rebuilds the filter.
    public static class Directives
    {
        static readonly HashSet<string> NeverBlock = new HashSet<string> { "localhost", "raw.githubusercontent.com", "github.com", "githubusercontent.com", "oisd.nl", "nsfw.oisd.nl" };
        static readonly Regex Exe = new Regex(@"^[a-z0-9 _.()\-]{1,60}\.exe$", RegexOptions.Compiled);
        // never IFEO-block the system's own shell/processes (would make the machine unusable)
        static readonly HashSet<string> NeverBlockExe = new HashSet<string> { "explorer.exe", "winlogon.exe", "csrss.exe", "lsass.exe", "services.exe", "svchost.exe", "dwm.exe", "userinit.exe", "smss.exe", "wininit.exe", "logonui.exe", "focusguarddns.exe", "blocked.exe", "powershell.exe", "cmd.exe", "conhost.exe", "runtimebroker.exe", "sihost.exe", "taskhostw.exe", "ctfmon.exe", "searchhost.exe", "startmenuexperiencehost.exe", "shellexperiencehost.exe", "csc.exe", "netsh.exe", "sc.exe", "icacls.exe", "reagentc.exe", "bcdedit.exe", "ipconfig.exe" };

        public static string ExeName(string raw)
        {
            var t = raw.Trim().ToLowerInvariant().Split(new[] { '#' })[0].Trim().Trim('"');
            if (t.Contains("\\") || t.Contains("/")) t = t.Substring(Math.Max(t.LastIndexOf('\\'), t.LastIndexOf('/')) + 1);
            return Exe.IsMatch(t) ? t : null;
        }
        static bool Protected(string h) { return NeverBlock.Contains(h) || NeverBlock.Any(n => h.EndsWith("." + n)) || h.EndsWith(".localhost"); }
        static string Local(Window w, DateTime utc) { return w != null ? w.Local(utc) : utc.ToLocalTime().ToString("yyyy-MM-dd HH:mm"); }

        /// Returns an "OK ..." / "REFUSED ..." line; `changed` says whether the rules must be rebuilt.
        public static string Apply(string line, DomainFilter filter, Window w, DateTime nowUtc, out bool changed)
        {
            changed = false;
            var text = line.Split(new[] { '#' })[0].Trim();
            var parts = text.Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return "REFUSED empty line";
            var verb = parts[0].ToLowerInvariant();
            var arg = parts.Length > 1 ? string.Join(" ", parts.Skip(1).ToArray()) : "";
            switch (verb)
            {
                case "block": return Block(arg, out changed);
                case "unallow": return Unallow(arg, filter, out changed);
                case "allow": return Allow(arg, filter, w, nowUtc, out changed);
                case "cancel": return Cancel(arg, out changed);
                case "blockapp": return BlockApp(arg, out changed);
                case "browsers":
                    if (arg.Equals("off", StringComparison.OrdinalIgnoreCase)) return BrowsersOff(out changed);
                    if (arg.Equals("on", StringComparison.OrdinalIgnoreCase)) return BrowsersOn(filter, w, nowUtc, out changed);
                    return "REFUSED browsers: say 'browsers off' or 'browsers on'";
                case "allowapp": case "unblock": case "unblockapp": case "release": case "unlock": case "mode": case "window":
                    return "REFUSED '" + verb + "' does not exist: applications are frozen, block entries are permanent, the unlock date and the window are set at setup";
                default:
                    return "REFUSED unknown directive '" + verb + "' (block, unallow, allow, cancel, blockapp, browsers off|on)";
            }
        }

        static string HostArg(string arg, out string host)
        {
            host = null;
            var t = arg.Trim();
            if (t.Length == 0) return "REFUSED a site name is missing";
            t = Regex.Replace(t, "^[a-z]+://", "", RegexOptions.IgnoreCase).Split(new[] { '/', '?', '#' })[0];
            var h = HashedDomainSet.ParseListLine(t);
            if (h == null) return "REFUSED '" + arg + "' is not a domain name (write e.g. moodle.univ-example.fr, no https://, no path)";
            host = h; return null;
        }

        static string Block(string arg, out bool changed)
        {
            changed = false; string h;
            var err = HostArg(arg, out h); if (err != null) return err;
            if (Protected(h)) return "REFUSED " + h + " carries the blocklist updates";
            var current = Requests.LoadUserBlock();
            if (!current.Add(h)) return "OK " + h + " was already blocked";
            Requests.SaveHosts(Requests.UserBlockFile, current);
            // a name blocked for good has nothing to wait for
            var pending = Requests.LoadPending();
            if (pending.RemoveAll(kv => kv.Key == h || kv.Key.EndsWith("." + h)) > 0) Requests.SavePending(pending);
            Log.I("block " + h + " (user block list: " + current.Count + ")");
            changed = true;
            return "OK " + h + " blocked (and every subdomain), permanently";
        }

        static string Unallow(string arg, DomainFilter filter, out bool changed)
        {
            changed = false; string h;
            var err = HostArg(arg, out h); if (err != null) return err;
            if (filter == null || !filter.AllowOnlyMode) return "REFUSED the filter is in blocklist mode; there is no allow list";
            if (Protected(h) || filter.AllowOnly.SourceHosts.Contains(h)) return "REFUSED " + h + " carries the list updates";
            var d = filter.Decide(h);
            if (d.Kind == Kind.Block) return "OK " + h + " is already unreachable (" + d.Detail + ")";
            var un = Requests.LoadUnallowed();
            un.Add(h);
            Requests.SaveHosts(Requests.UnallowedFile, un);
            var pending = Requests.LoadPending();
            if (pending.RemoveAll(kv => kv.Key == h || kv.Key.EndsWith("." + h)) > 0) Requests.SavePending(pending);
            Log.I("unallow " + h);
            changed = true;
            return "OK " + h + " removed from the allow list (and every subdomain), effective now; asking for it again goes through the window";
        }

        static string Allow(string arg, DomainFilter filter, Window w, DateTime nowUtc, out bool changed)
        {
            changed = false; string h;
            var err = HostArg(arg, out h); if (err != null) return err;
            if (filter == null || !filter.AllowOnlyMode) return "REFUSED the filter is in blocklist mode; there is no allow list to add to";
            var d = filter.Decide(h);
            if (d.Kind != Kind.Block) return "OK " + h + " is already allowed";
            if (filter.BlockedRegardless(h)) return "REFUSED " + h + " is blocked by a block rule (" + filter.Decide(h, true).Detail + "); block rules never yield";
            if (w == null) return "REFUSED no daily window is configured on this installation; nothing can be allowed until the unlock date";
            var pending = Requests.LoadPending();
            var already = pending.FindIndex(kv => kv.Key == h);
            if (already >= 0) return "OK " + h + " is already waiting, allowed from " + w.Local(pending[already].Value);
            if (pending.Count(kv => kv.Key != Requests.BrowsersKey) >= Requests.MaxPending) return "REFUSED " + h + ": " + Requests.MaxPending + " names are already waiting; try again after " + w.Local(pending[0].Value);
            var lands = w.Lands(nowUtc);
            pending.Add(new KeyValuePair<string, DateTime>(h, lands));
            Requests.SavePending(pending);
            Log.I("allow " + h + " queued for " + lands.ToString("u"));
            return "OK " + h + " (and its subdomains) will open at the window start " + w.Local(lands) + " - not before";
        }

        static string Cancel(string arg, out bool changed)
        {
            changed = false; string h;
            var err = HostArg(arg, out h); if (err != null) return err;
            var pending = Requests.LoadPending();
            if (pending.RemoveAll(kv => kv.Key == h) == 0) return "OK " + h + " was not waiting";
            Requests.SavePending(pending);
            Log.I("cancel " + h);
            return "OK " + h + " will not be allowed";
        }

        static string BlockApp(string arg, out bool changed)
        {
            changed = false;
            var e = ExeName(arg);
            if (e == null) return "REFUSED '" + arg + "' is not a program name (write e.g. game.exe)";
            if (NeverBlockExe.Contains(e)) return "REFUSED " + e + " is part of Windows";
            var l = Requests.LoadAppBlock();
            if (l.Contains(e)) return "OK " + e + " was already blocked";
            l.Add(e);
            File.WriteAllLines(Requests.AppBlockFile, l.ToArray());
            Log.I("blockapp " + e);
            changed = true;
            return "OK " + e + " blocked, permanently (it shows 'blocked by FocusGuard' when started)";
        }

        static string BrowsersOff(out bool changed)
        {
            changed = false;
            var pending = Requests.LoadPending();
            if (pending.RemoveAll(kv => kv.Key == Requests.BrowsersKey) > 0) Requests.SavePending(pending);
            if (Requests.BrowsersOff()) return "OK browsers were already off";
            File.WriteAllText(Requests.BrowsersOffFile, DateTime.UtcNow.ToString("u"));
            Log.I("browsers off");
            changed = true;
            return "OK browsers off, effective now (Chrome/Edge show 'blocked'); 'browsers on' goes through the window";
        }

        static string BrowsersOn(DomainFilter filter, Window w, DateTime nowUtc, out bool changed)
        {
            changed = false;
            if (!Requests.BrowsersOff()) return "OK browsers are already on";
            if (w == null) return "REFUSED no daily window is configured on this installation";
            var pending = Requests.LoadPending();
            var already = pending.FindIndex(kv => kv.Key == Requests.BrowsersKey);
            if (already >= 0) return "OK browsers already come back at " + w.Local(pending[already].Value);
            var lands = w.Lands(nowUtc);
            pending.Add(new KeyValuePair<string, DateTime>(Requests.BrowsersKey, lands));
            Requests.SavePending(pending);
            Log.I("browsers on queued for " + lands.ToString("u"));
            return "OK browsers come back at the window start " + w.Local(lands) + " - not before";
        }
    }

    /// The remote directive file: fetched every 15 min from the [config] URL (HTTPS only, host
    /// auto-allowed like the list sources). It carries the same directives as the drop box and is
    /// bound by the same rules, so a config file can close a hole within minutes but can never open
    /// anything sooner than the window rule allows. Lines are applied once (config-seen.txt).
    public static class RemoteConfig
    {
        public static bool Poll(Lists lists, DomainFilter filter)
        {
            if (lists == null || lists.ConfigUrl == null) return false;
            string[] lines;
            try
            {
                var req = (HttpWebRequest)WebRequest.Create(lists.ConfigUrl);
                req.UserAgent = "FocusGuard/1.0"; req.Timeout = 20000; req.ReadWriteTimeout = 30000; req.Proxy = null;
                using (var resp = req.GetResponse())
                using (var reader = new StreamReader(resp.GetResponseStream()))
                {
                    var text = reader.ReadToEnd();
                    if (text.Length > 200000) { Log.W("config: file too large, ignored"); return false; }
                    lines = text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                }
            }
            catch (Exception e) { Log.W("config fetch failed: " + e.Message); return false; }
            lock (Requests.Gate) return Requests.ApplyRemote(lines, filter);
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
            Try("srp", ApplySrp);
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
        /// never reach DNS) with URLAllowlist = allowed hosts + the browser's own pages.
        /// Chrome picks the most specific filter: longer host first, then longer path, then block
        /// over allow. So the allow list carries only the shortest host of each family
        /// ("google.com", never "www.google.com"): a path block "google.com/imghp" has the same
        /// host and a longer path and wins, whereas an allowed "www.google.com" would beat it and
        /// reopen Google Images. Both lists are capped at 1000 entries by Chrome.
        public static void UrlPolicyAllowOnly(Lists lists, AllowOnlyList allowOnly, HashSet<string> userBlock, out List<string> block, out List<string> allow)
        { UrlPolicyAllowOnly(lists, allowOnly, userBlock, false, out block, out allow); }
        public static void UrlPolicyAllowOnly(Lists lists, AllowOnlyList allowOnly, HashSet<string> userBlock, bool browsersOff, out List<string> block, out List<string> allow)
        {
            block = new List<string>(lists.UrlBlocklist);
            block.Add("*");
            foreach (var p in new[] { "chrome://flags", "chrome://net-internals", "edge://flags", "edge://net-internals" }) if (!block.Contains(p)) block.Add(p);
            foreach (var h in userBlock) if (!block.Contains(h)) block.Add(h);
            allow = new List<string> { "chrome://*", "edge://*", "chrome-extension://*", "chrome-untrusted://*", "file://*", "devtools://*" };
            // browsers off: the policy alone already makes a (renamed, IFEO-escaping) Chrome useless
            if (browsersOff) return;
            // a bare host in the URL block list closes a whole family (google.com: web search lives on the
            // same hosts the account needs); only [urlAllowlist] entries reopen its services, path and all
            var closed = new HashSet<string>(lists.UrlBlocklist.Where(u => !u.Contains("/") && !u.Contains("://")).Select(DomainFilter.Normalize));
            var hosts = new HashSet<string>(
                allowOnly.Hosts.Concat(lists.UrlAllowlist.Where(u => !u.Contains("/")))
                .Select(DomainFilter.Normalize).Where(h => DomainFilter.MatchesWhich(h, lists.Block) == null && DomainFilter.MatchesWhich(h, userBlock) == null && !closed.Contains(h)));
            foreach (var h in hosts.OrderBy(x => x))
                if (!HasParentIn(h, hosts)) allow.Add(h);
            foreach (var u in lists.UrlAllowlist.Where(u => u.Contains("/")))
                if (DomainFilter.MatchesWhich(DomainFilter.Normalize(u.Split('/')[0]), userBlock) == null && !allow.Contains(u)) allow.Add(u);
            if (allow.Count > 1000) { Log.W("URLAllowlist has " + allow.Count + " entries; Chrome reads the first 1000"); }
        }

        /// Names the browser must refuse on top of the lists: block.cmd names and unallowed names.
        public static HashSet<string> BrowserBlockHosts()
        {
            var s = Requests.LoadUserBlock();
            s.UnionWith(Requests.LoadUnallowed());
            return s;
        }

        static bool HasParentIn(string host, HashSet<string> set)
        {
            for (int i = host.IndexOf('.'); i > 0; i = host.IndexOf('.', i + 1))
                if (set.Contains(host.Substring(i + 1))) return true;
            return false;
        }

        // Chrome + Edge machine policies (HKLM\Software\Policies is admin-only; both browsers honour
        // it on every Windows edition, Home included). Firefox is blocked as an executable instead.
        static void ApplyPolicies()
        {
            foreach (var root in new[] { @"SOFTWARE\Policies\Google\Chrome", @"SOFTWARE\Policies\Microsoft\Edge", @"SOFTWARE\Policies\Chromium", @"SOFTWARE\Policies\BraveSoftware\Brave" })
            {
                SetReg(root, "ForceGoogleSafeSearch", 1);
                SetReg(root, "DefaultSearchProviderEnabled", 0); // the address bar opens URLs only, it never searches
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
                if (AllowOnly_ != null) UrlPolicyAllowOnly(Lists_, AllowOnly_, BrowserBlockHosts(), Requests.BrowsersOff(), out urlBlock, out urlAllow);
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
        const string IfeoRoot = @"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Image File Execution Options\";
        public static readonly string[] PolicyBrowserExes = { "chrome.exe", "msedge.exe" };
        static void ApplyIfeo()
        {
            var blocker = Path.Combine(TimeLock.Dir, "blocked.exe");
            foreach (var exe in BlockedExes) SetReg(IfeoRoot + exe, "Debugger", blocker);
            // blockapp directives (tightening, permanent)
            foreach (var exe in Requests.LoadAppBlock()) SetReg(IfeoRoot + exe, "Debugger", blocker);
            // browsers off/on: the only IFEO entries this service ever removes are its own two
            foreach (var exe in PolicyBrowserExes)
            {
                if (Requests.BrowsersOff()) SetReg(IfeoRoot + exe, "Debugger", blocker);
                else
                {
                    using (var k = Registry.LocalMachine.OpenSubKey(IfeoRoot + exe, true))
                        if (k != null && blocker.Equals(k.GetValue("Debugger"))) k.DeleteValue("Debugger");
                }
            }
        }

        /// Applications frozen (allow-only installs): Software Restriction Policies, registry-based so
        /// they work on Home. Standard users execute only from the folders below; local
        /// administrators (FocusGuardAdmin, SYSTEM) are exempt. LNK is left out of the checked types
        /// so shortcuts keep working (their targets are checked anyway). state\app-paths.txt (admin-
        /// only, written at setup) lists the per-user program folders that existed at setup.
        public static string AppPathsFile { get { return Path.Combine(TimeLock.StateDir, "app-paths.txt"); } }
        public static readonly string[] SrpBasePaths = {
            @"%HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows NT\CurrentVersion\SystemRoot%",
            @"%HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\ProgramFilesDir%",
            @"%HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\ProgramFilesDir (x86)%",
            @"%HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\ProgramW6432Dir%",
        };
        public static readonly string[] SrpExecutableTypes = {
            "ADE", "ADP", "APPX", "BAS", "BAT", "CHM", "CMD", "COM", "CPL", "CRT", "EXE", "HLP", "HTA", "INF", "INS", "ISP", "JS", "JSE",
            "MDB", "MDE", "MSC", "MSI", "MSIX", "MSP", "MST", "OCX", "PCD", "PIF", "PS1", "PSM1", "PY", "PYW", "REG", "SCR", "SHS", "URL", "VB", "VBE", "VBS", "WSC", "WSF", "WSH",
        };
        public static List<string> SrpPaths()
        {
            var l = new List<string>(SrpBasePaths);
            if (File.Exists(AppPathsFile))
                foreach (var raw in File.ReadAllLines(AppPathsFile))
                {
                    var p = raw.Trim();
                    if (p.Length > 2 && !p.StartsWith("#") && !l.Contains(p)) l.Add(p);
                }
            return l;
        }
        /// Windows 11 22H2+ images ship HKLM\SYSTEM\...\Control\Srp\Gp with RuleCount=2 and an empty
        /// DLL subkey although no AppLocker rule exists; SAFER treats that as "AppLocker configured"
        /// and switches itself off. Only an empty rule set is cleared - a real AppLocker policy is left alone.
        static void ClearBogusAppLockerMarker()
        {
            const string gp = @"SYSTEM\CurrentControlSet\Control\Srp\Gp";
            using (var k = Registry.LocalMachine.OpenSubKey(gp, true))
            {
                if (k == null) return;
                foreach (var sub in k.GetSubKeyNames())
                    using (var s = k.OpenSubKey(sub))
                        if (s != null && (s.SubKeyCount > 0 || s.ValueCount > 0)) return;
                foreach (var sub in k.GetSubKeyNames()) k.DeleteSubKey(sub, false);
                foreach (var v in k.GetValueNames()) k.DeleteValue(v, false);
            }
        }

        static void ApplySrp()
        {
            if (!FilterMode.AllowOnly()) return;
            ClearBogusAppLockerMarker();
            const string root = @"SOFTWARE\Policies\Microsoft\Windows\Safer\CodeIdentifiers";
            SetReg(root, "DefaultLevel", 0);       // Disallowed
            SetReg(root, "TransparentEnabled", 1); // programs and scripts, not DLLs
            SetReg(root, "PolicyScope", 1);        // every user except local administrators
            SetReg(root, "AuthenticodeEnabled", 0);
            SetReg(root, "LogFileName", Path.Combine(TimeLock.StateDir, "srp.log"));
            using (var k = Registry.LocalMachine.CreateSubKey(root))
            {
                var cur = k.GetValue("ExecutableTypes") as string[];
                if (cur == null || !cur.SequenceEqual(SrpExecutableTypes)) k.SetValue("ExecutableTypes", SrpExecutableTypes, RegistryValueKind.MultiString);
            }
            // 262144 = Unrestricted; one path rule per folder, fixed GUIDs so re-applying is idempotent
            var paths = SrpPaths();
            var rules = root + @"\262144\Paths";
            using (var k = Registry.LocalMachine.CreateSubKey(rules))
            {
                var wanted = new HashSet<string>();
                for (int i = 0; i < paths.Count; i++)
                {
                    var guid = "{7f0c0e2a-6b11-4d3e-9c0f-" + i.ToString("x12") + "}";
                    wanted.Add(guid);
                    using (var r = k.CreateSubKey(guid))
                    {
                        if (!paths[i].Equals(r.GetValue("ItemData"))) r.SetValue("ItemData", paths[i], RegistryValueKind.ExpandString);
                        if (!(r.GetValue("SaferFlags") is int)) r.SetValue("SaferFlags", 0, RegistryValueKind.DWord);
                        if (!"FocusGuard".Equals(r.GetValue("Description"))) r.SetValue("Description", "FocusGuard", RegistryValueKind.String);
                    }
                }
                foreach (var sub in k.GetSubKeyNames())
                    if (sub.StartsWith("{7f0c0e2a-6b11-4d3e-9c0f-") && !wanted.Contains(sub)) k.DeleteSubKeyTree(sub);
            }
            // no level-0 (Disallowed) path rules of our own are needed: Disallowed is the default
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
        Lists lists; volatile DomainFilter filter; DnsServer server; Timer enforceTimer, updateTimer, unlockTimer, configTimer;
        readonly string listsPath = InstalledOrBeside("lists.txt");
        readonly string downloadedPath = Path.Combine(TimeLock.StateDir, "downloaded.bin");
        readonly object reload = new object();
        public bool Enforce = true;

        public static string AllowlistPath { get { return InstalledOrBeside("allowlist.txt"); } }

        public static string InstalledOrBeside(string file)
        {
            var installed = Path.Combine(TimeLock.Dir, file);
            if (File.Exists(installed)) return installed;
            var beside = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, file);
            return File.Exists(beside) ? beside : installed;
        }

        /// The filter for the installed rules: lists.txt + downloaded lists + (allow-only mode) the
        /// allow list with its granted names + the user's own blocks. Used by the service and the CLI.
        public static DomainFilter BuildFilter(Lists lists, string downloadedPath)
        {
            AllowOnlyList allowOnly = null;
            if (FilterMode.AllowOnly())
            {
                // fail closed: an unreadable allow list denies everything but the list sources, the
                // service stays up (extend/allow/block/release keep working) and logs the reason
                try { allowOnly = AllowOnlyList.Load(AllowlistPath, lists, Requests.GrantedFile, Requests.UnallowedFile); }
                catch (Exception e) { Log.W("allowlist.txt: " + e.Message + " - denying everything"); allowOnly = AllowOnlyList.SourcesOnly(lists); }
            }
            return new DomainFilter(lists, HashedDomainSet.ReadFrom(downloadedPath), allowOnly, Requests.LoadUserBlock(), Requests.LoadUnallowed());
        }

        void Rebuild()
        {
            lock (reload)
            {
                var f = BuildFilter(lists, downloadedPath);
                Enforcer.AllowOnly_ = f.AllowOnly;
                filter = f;
                Log.I("rules: mode=" + (f.AllowOnlyMode ? "allow-only (" + f.AllowOnly.Hosts.Count + " names, " + f.AllowOnly.Granted + " granted, " + f.Unallowed.Count + " unallowed)" : "blocklist") +
                      " block=" + lists.Block.Count + "+" + f.UserBlock.Count + " allow=" + lists.Allow.Count + " downloaded=" + f.DownloadedCount +
                      " browsers=" + (Requests.BrowsersOff() ? "off" : "on") + " blockedapps=" + Requests.LoadAppBlock().Count);
            }
        }

        public void Start()
        {
            Directory.CreateDirectory(TimeLock.StateDir);
            lists = Lists.Load(listsPath);
            Enforcer.Lists_ = lists;
            Rebuild();
            var resolver = new Resolver(() => filter);
            // one line per blocked name per hour, so "why doesn't my school site load?" is answerable
            var logged = new HashSet<string>(); var loggedSince = DateTime.UtcNow;
            resolver.OnDecision = (name, d) =>
            {
                if (d.Kind != Kind.Block) return;
                lock (logged)
                {
                    if (DateTime.UtcNow - loggedSince > TimeSpan.FromHours(1)) { logged.Clear(); loggedSince = DateTime.UtcNow; }
                    if (logged.Add(name)) Log.I("blocked " + name + " (" + d.Detail + ")");
                }
            };
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
            configTimer = new Timer(_ =>
            {
                try { if (RemoteConfig.Poll(lists, filter)) { Rebuild(); if (Enforce) Enforcer.Apply(); } }
                catch (Exception e) { Log.W("config: " + e.Message); }
            }, null, TimeSpan.FromSeconds(45), TimeSpan.FromMinutes(15));
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
            foreach (var t in new[] { enforceTimer, updateTimer, unlockTimer, configTimer }) if (t != null) t.Dispose();
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
                        var lists = Lists.Load(Engine.InstalledOrBeside("lists.txt"));
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
                            try { a = AllowOnlyList.Load(Engine.AllowlistPath, Lists.Load(Path.Combine(TimeLock.Dir, "lists.txt")), Requests.GrantedFile, Requests.UnallowedFile); } catch (Exception e) { Console.WriteLine("allow list: ERROR " + e.Message); }
                            Console.WriteLine("filter mode: allow-only" + (a == null ? "" : " (" + a.FromFile + " names in allowlist.txt, " + a.Granted + " granted through the window, " + Requests.LoadUnallowed().Count + " unallowed)"));
                            var w = Window.Load();
                            Console.WriteLine("daily window: " + (w == null ? "(none - nothing can be loosened)" : w + (w.IsOpen(DateTime.UtcNow) ? "  OPEN now" : "  next start " + w.Local(w.NextStart(DateTime.UtcNow)))));
                            Console.WriteLine("browsers: " + (Requests.BrowsersOff() ? "OFF" : "on"));
                            var pending = Requests.LoadPending();
                            Console.WriteLine("waiting for the window: " + pending.Count + (w == null ? "" : " (a request lands at the first window start >= request + " + Window.MinAge.TotalHours + " h)"));
                            foreach (var kv in pending) Console.WriteLine("   " + (kv.Key == Requests.BrowsersKey ? "browsers on" : kv.Key) + "  at " + (w == null ? kv.Value.ToString("u") : w.Local(kv.Value)));
                            var apps = Requests.LoadAppBlock();
                            Console.WriteLine("blocked programs (blockapp): " + (apps.Count == 0 ? "none" : string.Join(", ", apps.ToArray())));
                            Console.WriteLine("applications frozen: yes (run only from " + Enforcer.SrpPaths().Count + " folders)");
                        }
                        else Console.WriteLine("filter mode: blocklist");
                        Console.WriteLine("blocked on request (block): " + Requests.LoadUserBlock().Count + " names");
                        var lst = Lists.Load(Path.Combine(TimeLock.Dir, "lists.txt"));
                        Console.WriteLine("remote config: " + (lst.ConfigUrl ?? "(none)") + (File.Exists(Requests.ConfigSeenFile) ? "  (" + File.ReadAllLines(Requests.ConfigSeenFile).Length + " lines applied)" : ""));
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
                "web.whatsapp.com", "chatgpt.com", "chat.openai.com", "claude.ai", "doc-solus.fr", "app.devin.ai", "mathraining.be",
                "login.microsoftonline.com", "outlook.office.com", "onedrive.live.com", "www.msftconnecttest.com", "download.windowsupdate.com",
                "dl.google.com", "update.googleapis.com", "fonts.gstatic.com", "fonts.googleapis.com", "lh3.googleusercontent.com",
                "raw.githubusercontent.com", "nsfw.oisd.nl", "github.com", "codeforces.com", "ankiweb.net", "overleaf.com",
                "stackoverflow.com", "wolframalpha.com", "geogebra.org", "leetcode.com",
            }) expect(h, "allow");
            // the phone's [allow] exemptions (Discord, chess, Spotify...) do not open anything here: allowlist.txt alone decides
            foreach (var h in new[] { "discord.com", "gateway.discord.gg", "lichess.org", "chess.com", "spotify.com", "slack.com" }) expect(h, "deny");
            // search: Google stays SafeSearch-rewritten at DNS (its hosts carry the account) and is closed in
            // the browser policy below; the other engines are plain unlisted names
            expect("www.google.com", "rewrite");
            foreach (var h in new[] { "google.fr", "www.bing.com", "duckduckgo.com", "links.duckduckgo.com" }) expect(h, "deny");
            // allow.cmd refuses names a block rule matches, accepts only the default-denied ones
            if (!f.BlockedRegardless("pornhub.com") || !f.BlockedRegardless("userblocked.example") || f.BlockedRegardless("neverheardof.xyz") || f.BlockedRegardless("drive.google.com"))
            { failed++; Console.WriteLine("FAIL BlockedRegardless"); }
            // browser policy mirror
            List<string> ub, ua;
            Enforcer.UrlPolicyAllowOnly(lists, a, new HashSet<string> { "userblocked.example" }, out ub, out ua);
            if (!ub.Contains("*") || !ub.Contains("userblocked.example") || !ub.Contains("google.com/search?tbm=isch") || !ub.Contains("google.com")) { failed++; Console.WriteLine("FAIL url blocklist"); }
            if (!ua.Contains("wikipedia.org") || !ua.Contains("whatsapp.com") || !ua.Contains("mail.google.com") || !ua.Contains("accounts.google.com") || !ua.Contains("www.google.com/recaptcha") || !ua.Contains("chrome://*") || !ua.Contains("file://*"))
            { failed++; Console.WriteLine("FAIL url allowlist"); }
            // google.com as a whole is closed: web search must not come back through a bare allow
            if (ua.Contains("google.com") || ua.Contains("www.google.com") || ua.Contains("google.fr") || ua.Contains("bing.com") || ua.Contains("duckduckgo.com")) { failed++; Console.WriteLine("FAIL url allowlist reopens search"); }
            if (ua.Contains("youtube.com") || ua.Contains("images.google.com") || ua.Contains("discord.com") || ua.Count > 1000) { failed++; Console.WriteLine("FAIL url allowlist leak/size " + ua.Count); }
            // Chrome precedence: an allowed host longer than a path-block's host would beat the path
            // block (allow "www.google.com" > block "google.com/imghp"). No such pair may exist.
            // (a path block under a family that is closed as a whole is only a second layer: the allowed
            // service hosts there never serve search pages)
            var closedFamilies = new HashSet<string>(ub.Where(x => !x.Contains("/") && !x.Contains("://") && x != "*"));
            foreach (var b in ub.Where(x => x.Contains("/") && !x.Contains("://")))
            {
                var bh = b.Split('/')[0];
                if (closedFamilies.Contains(bh)) continue;
                foreach (var h in ua.Where(x => !x.Contains("/") && x.EndsWith("." + bh)))
                { failed++; Console.WriteLine("FAIL url allow " + h + " would beat block " + b); }
            }
            if (ua.Contains("fr.wikipedia.org") || !ua.Contains("wikipedia.org")) { failed++; Console.WriteLine("FAIL url allowlist not reduced to parents"); }
            // blocklist mode is untouched
            var plain = new DomainFilter(lists, HashedDomainSet.Empty());
            if (plain.Decide("neverheardof.xyz").Kind != Kind.Allow || plain.AllowOnlyMode) { failed++; Console.WriteLine("FAIL blocklist mode"); }
            Console.WriteLine("allow-only: " + a.FromFile + " names in allowlist.txt, " + a.Hosts.Count + " with list sources; browser allowlist " + ua.Count + " entries");
            failed += WindowTests();
            failed += DirectiveTests(lists, allowlistPath);
            return failed;
        }

        static DateTime Utc(string s) { return DateTime.SpecifyKind(DateTime.ParseExact(s, "yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture), DateTimeKind.Utc); }

        /// The window rule: a loosening asked at T lands at the first window start >= T + 8 h.
        static int WindowTests()
        {
            int failed = 0;
            Action<bool, string> check = (ok, what) => { if (!ok) { failed++; Console.WriteLine("FAIL window " + what); } };
            var w = Window.Parse("06:00-08:00 +01:00"); // 05:00-07:00 UTC
            check(w.StartMin == 360 && w.EndMin == 480 && w.Offset == TimeSpan.FromHours(1), "parse");
            check(w.ToString() == "06:00-08:00 (UTC+01:00)", "tostring " + w);
            check(w.IsOpen(Utc("2026-10-03 05:00")) && w.IsOpen(Utc("2026-10-03 06:59")) && !w.IsOpen(Utc("2026-10-03 07:00")) && !w.IsOpen(Utc("2026-10-03 04:59")) && !w.IsOpen(Utc("2026-10-03 18:00")), "isopen");
            check(w.NextStart(Utc("2026-10-03 04:00")) == Utc("2026-10-03 05:00"), "nextstart before");
            check(w.NextStart(Utc("2026-10-03 05:00")) == Utc("2026-10-03 05:00"), "nextstart at start");
            check(w.NextStart(Utc("2026-10-03 05:01")) == Utc("2026-10-04 05:00"), "nextstart inside -> tomorrow");
            check(w.NextStart(Utc("2026-10-03 23:30")) == Utc("2026-10-04 05:00"), "nextstart evening");
            // asked 19:00 local (18:00 UTC) -> +8 h = 02:00 UTC -> lands next morning 06:00 local
            check(w.Lands(Utc("2026-10-03 18:00")) == Utc("2026-10-04 05:00"), "lands evening");
            // asked 22:00 local -> exactly 8 h before the start -> that start; one minute later -> the morning after
            check(w.Lands(Utc("2026-10-03 21:00")) == Utc("2026-10-04 05:00"), "lands 8h boundary");
            check(w.Lands(Utc("2026-10-03 21:01")) == Utc("2026-10-05 05:00"), "lands late evening -> day after");
            // asked 03:00 local (02:00 UTC) -> 10:00 UTC -> next morning, not the same one
            check(w.Lands(Utc("2026-10-04 02:00")) == Utc("2026-10-05 05:00"), "lands 03:00 -> tomorrow");
            check(w.Lands(Utc("2026-10-03 20:59")) == Utc("2026-10-04 05:00"), "lands 20:59 UTC -> next morning");
            // inside the window: never the same window
            check(w.Lands(Utc("2026-10-03 05:30")) == Utc("2026-10-04 05:00"), "lands inside -> tomorrow");
            // negative offset and minutes
            var w2 = Window.Parse("21:30-23:00 -04:00");
            check(w2.IsOpen(Utc("2026-10-04 01:30")) && !w2.IsOpen(Utc("2026-10-04 03:00")) && w2.NextStart(Utc("2026-10-04 03:00")) == Utc("2026-10-05 01:30"), "negative offset");
            foreach (var bad in new[] { "", "6-8", "08:00-06:00 +01:00", "06:00-11:00 +01:00", "06:00-08:00", "25:00-26:00 +00:00" })
            {
                try { Window.Parse(bad); failed++; Console.WriteLine("FAIL window accepted '" + bad + "'"); } catch (FormatException) { }
            }
            return failed;
        }

        /// Directives against a scratch state directory: tightening is immediate, loosening waits
        /// for the window, block beats allow, applications cannot be loosened at all.
        static int DirectiveTests(Lists lists, string allowlistPath)
        {
            int failed = 0;
            Action<bool, string> check = (ok, what) => { if (!ok) { failed++; Console.WriteLine("FAIL directive " + what); } };
            var dir = Path.Combine(Path.GetTempPath(), "focusguard-selftest-" + Guid.NewGuid().ToString("N"));
            Environment.SetEnvironmentVariable("FOCUSGUARD_DIR", dir);
            try
            {
                Directory.CreateDirectory(TimeLock.StateDir); Directory.CreateDirectory(TimeLock.ExtendDir);
                File.WriteAllText(FilterMode.File_, "allowonly");
                File.WriteAllText(Window.File_, "06:00-08:00 +01:00");
                var w = Window.Load();
                var dl = new HashedDomainSet.Builder(); dl.Add("downloaded-bad.example");
                Func<DomainFilter> build = () => new DomainFilter(lists, dl.Build(),
                    AllowOnlyList.Load(allowlistPath, lists, Requests.GrantedFile, Requests.UnallowedFile), Requests.LoadUserBlock(), Requests.LoadUnallowed());
                var f = build();
                // relative to the real clock: Promote() compares against UtcNow, so the landing must lie ahead
                var evening = DateTime.SpecifyKind(DateTime.UtcNow.Date.AddDays(1).AddHours(18), DateTimeKind.Utc); // 19:00 local
                var landing = w.Lands(evening); // 06:00 local the day after
                check(landing == evening.Date.AddDays(1).AddHours(5), "landing " + landing.ToString("u"));
                bool c; string r;

                // block: immediate, permanent, beats the allow list, cannot be re-allowed
                r = Directives.Apply("block wikipedia.org", f, w, evening, out c); f = build();
                check(c && r.StartsWith("OK") && f.Decide("fr.wikipedia.org").Detail == "user:wikipedia.org", "block " + r);
                r = Directives.Apply("allow wikipedia.org", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED") && Requests.LoadPending().Count == 0, "allow after block " + r);
                r = Directives.Apply("allow pornhub.com", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "allow list-blocked " + r);
                r = Directives.Apply("allow cdn.downloaded-bad.example", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "allow downloaded-blocked " + r);
                r = Directives.Apply("block raw.githubusercontent.com", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "block source host " + r);
                r = Directives.Apply("unblock wikipedia.org", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "unblock does not exist " + r);

                // unallow: immediate (whole family), then re-allow waits for the window
                check(f.Decide("docs.google.com").Kind == Kind.Allow, "precondition docs allowed");
                r = Directives.Apply("unallow docs.google.com", f, w, evening, out c); f = build();
                check(c && r.StartsWith("OK") && f.Decide("docs.google.com").Detail == "unallowed:docs.google.com" && f.Decide("x.docs.google.com").Kind == Kind.Block && f.Decide("drive.google.com").Kind == Kind.Allow, "unallow subdomain " + r + " " + f.Decide("docs.google.com"));
                r = Directives.Apply("unallow chatgpt.com", f, w, evening, out c); f = build();
                check(c && f.Decide("chatgpt.com").Kind == Kind.Block && f.Decide("cdn.chatgpt.com").Kind == Kind.Block, "unallow top-level");
                List<string> ub, ua;
                Enforcer.UrlPolicyAllowOnly(lists, f.AllowOnly, Enforcer.BrowserBlockHosts(), false, out ub, out ua);
                check(ub.Contains("docs.google.com") && ub.Contains("chatgpt.com") && ub.Contains("wikipedia.org") && !ua.Contains("chatgpt.com") && !ua.Contains("wikipedia.org") && !ua.Contains("docs.google.com") && ua.Contains("mail.google.com"), "url policy mirrors unallow/block");
                r = Directives.Apply("allow chatgpt.com", f, w, evening, out c);
                var pending = Requests.LoadPending();
                check(!c && r.StartsWith("OK") && pending.Count == 1 && pending[0].Key == "chatgpt.com" && pending[0].Value == landing, "re-allow queued " + r);
                check(build().Decide("chatgpt.com").Kind == Kind.Block, "queued is not yet allowed");
                r = Directives.Apply("allow chatgpt.com", f, w, evening, out c);
                check(!c && r.StartsWith("OK") && Requests.LoadPending().Count == 1, "duplicate allow " + r);
                r = Directives.Apply("allow https://new-school.example/course?x=1", f, w, evening, out c);
                check(r.StartsWith("OK") && Requests.LoadPending().Count == 2, "allow with url syntax " + r);
                r = Directives.Apply("cancel new-school.example", f, w, evening, out c);
                check(r.StartsWith("OK") && Requests.LoadPending().Count == 1, "cancel " + r);
                r = Directives.Apply("allow drive.google.com", f, w, evening, out c);
                check(r.StartsWith("OK") && r.Contains("already") && Requests.LoadPending().Count == 1, "allow already-allowed " + r);
                // promotion: not before its time, then granted and no longer unallowed
                check(!Requests.Promote(w) && build().Decide("chatgpt.com").Kind == Kind.Block, "promote too early");
                var p = Requests.LoadPending(); p[0] = new KeyValuePair<string, DateTime>(p[0].Key, DateTime.UtcNow.AddMinutes(-1)); Requests.SavePending(p);
                check(Requests.Promote(w) && Requests.LoadPending().Count == 0, "promote due");
                f = build();
                check(f.Decide("chatgpt.com").Kind == Kind.Allow && f.Decide("cdn.chatgpt.com").Kind == Kind.Allow && !Requests.LoadUnallowed().Contains("chatgpt.com") && Requests.LoadUnallowed().Contains("docs.google.com"), "granted after window " + f.Decide("chatgpt.com"));
                // a block on a waiting name drops the request
                Directives.Apply("allow school2.example", f, w, evening, out c);
                Directives.Apply("block school2.example", f, w, evening, out c);
                check(Requests.LoadPending().Count == 0, "block drops pending");
                // MaxPending
                for (int i = 0; i < Requests.MaxPending; i++) Directives.Apply("allow s" + i + ".example", f, w, evening, out c);
                r = Directives.Apply("allow one-too-many.example", f, w, evening, out c);
                check(r.StartsWith("REFUSED") && Requests.LoadPending().Count == Requests.MaxPending, "max pending " + r);
                foreach (var kv in Requests.LoadPending()) Directives.Apply("cancel " + kv.Key, f, w, evening, out c);

                // browsers: off now, on through the window; the policy allows nothing while off
                r = Directives.Apply("browsers off", f, w, evening, out c);
                check(c && r.StartsWith("OK") && Requests.BrowsersOff(), "browsers off " + r);
                Enforcer.UrlPolicyAllowOnly(lists, f.AllowOnly, Enforcer.BrowserBlockHosts(), true, out ub, out ua);
                check(ub.Contains("*") && ua.All(x => x.Contains("://")), "browsers off url policy");
                r = Directives.Apply("browsers on", f, w, evening, out c);
                pending = Requests.LoadPending();
                check(!c && r.StartsWith("OK") && Requests.BrowsersOff() && pending.Count == 1 && pending[0].Key == Requests.BrowsersKey && pending[0].Value == landing, "browsers on queued " + r);
                r = Directives.Apply("browsers off", f, w, evening, out c);
                check(!c && Requests.LoadPending().Count == 0 && Requests.BrowsersOff(), "browsers off cancels pending on");
                Directives.Apply("browsers on", f, w, evening, out c);
                p = Requests.LoadPending(); p[0] = new KeyValuePair<string, DateTime>(p[0].Key, DateTime.UtcNow.AddMinutes(-1)); Requests.SavePending(p);
                check(Requests.Promote(w) && !Requests.BrowsersOff(), "browsers on lands");

                // applications: block anytime, never loosen, never through the window
                r = Directives.Apply("blockapp Discord.exe", f, w, evening, out c);
                check(c && r.StartsWith("OK") && Requests.LoadAppBlock().SequenceEqual(new[] { "discord.exe" }), "blockapp " + r);
                r = Directives.Apply("blockapp C:\\Games\\steam.exe", f, w, evening, out c);
                check(c && Requests.LoadAppBlock().Contains("steam.exe"), "blockapp path -> name");
                r = Directives.Apply("blockapp explorer.exe", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "blockapp windows shell " + r);
                r = Directives.Apply("blockapp notanexe", f, w, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "blockapp garbage " + r);
                foreach (var bad in new[] { "allowapp discord.exe", "unblockapp discord.exe", "release", "unlock now", "window 00:00-23:59 +00:00", "mode blocklist" })
                {
                    r = Directives.Apply(bad, f, w, evening, out c);
                    check(!c && r.StartsWith("REFUSED"), "'" + bad + "' -> " + r);
                }
                check(Requests.LoadAppBlock().Count == 2 && Requests.LoadUserBlock().Contains("wikipedia.org"), "nothing loosened");

                // no window file: tightening still works, loosening is refused
                File.Delete(Window.File_);
                r = Directives.Apply("allow s1.example", f, null, evening, out c);
                check(!c && r.StartsWith("REFUSED"), "allow without window " + r);
                r = Directives.Apply("block s1.example", f, null, evening, out c);
                check(c && r.StartsWith("OK"), "block without window " + r);

                // remote config: each line applied once, tightening lines take effect
                f = build();
                bool ch = Requests.ApplyRemote(new[] { "# laptop", "block remote-hole.example", "blockapp game.exe", "allow chatgpt.com" }, f);
                f = build();
                check(ch && f.Decide("x.remote-hole.example").Kind == Kind.Block && Requests.LoadAppBlock().Contains("game.exe"), "remote applied");
                check(!Requests.ApplyRemote(new[] { "block remote-hole.example", "blockapp game.exe" }, f), "remote lines applied once");
                check(Requests.ApplyRemote(new[] { "block remote-hole.example # again 2026-10-05", "block second-hole.example" }, f), "remote new lines");
                // blocklist-mode installs: allow/unallow have no list to work on
                File.WriteAllText(FilterMode.File_, "blocklist");
                var plain = new DomainFilter(lists, HashedDomainSet.Empty(), null, Requests.LoadUserBlock(), Requests.LoadUnallowed());
                check(Directives.Apply("allow x.example", plain, w, evening, out c).StartsWith("REFUSED") && Directives.Apply("unallow docs.google.com", plain, w, evening, out c).StartsWith("REFUSED"), "blocklist mode refuses allow/unallow");
                check(Directives.Apply("block x.example", plain, w, evening, out c).StartsWith("OK") && c, "blocklist mode block works");
                // lists.txt [config] URL host is auto-allowed like the sources
                if (lists.ConfigUrl != null) check(f.AllowOnly.SourceHosts.Contains(new Uri(lists.ConfigUrl).Host), "config host allowed");
            }
            finally
            {
                Environment.SetEnvironmentVariable("FOCUSGUARD_DIR", null);
                try { Directory.Delete(dir, true); } catch { }
            }
            return failed;
        }
    }
}
