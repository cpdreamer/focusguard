// Target of the Image File Execution Options "Debugger" entries written by FocusGuardDns:
// Windows launches this instead of the blocked program. It just says so and exits.
using System;
using System.IO;
using System.Windows.Forms;

namespace FocusGuard
{
    public static class Blocked
    {
        [STAThread]
        public static int Main(string[] args)
        {
            var exe = args.Length > 0 ? Path.GetFileName(args[0]) : "this program";
            MessageBox.Show(exe + " is blocked by FocusGuard until the commitment ends.",
                "FocusGuard", MessageBoxButtons.OK, MessageBoxIcon.Stop, MessageBoxDefaultButton.Button1, MessageBoxOptions.ServiceNotification);
            return 1;
        }
    }
}
