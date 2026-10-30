using System;
using System.IO;
using System.Text.Json;
using Microsoft.Win32;

namespace KankiVPN
{
    /// <summary>The app's own choices (not the service's): language and open at sign-in.
    /// Kept in %AppData%\KankiVPN\ui.json for this Windows user.</summary>
    public sealed class Prefs
    {
        public string Lang { get; set; } = "fa";
        public string LastServer { get; set; } = "";
        public bool TrayHintShown { get; set; }
        public string Theme { get; set; } = "yellow";
        /// <summary>smart, gaming or anti (the three tiles on Home); a server picked by hand wins.</summary>
        public string Mode { get; set; } = "smart";
        public bool Manual { get; set; }

        static string PathOf() => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "KankiVPN", "ui.json");

        public static Prefs Load()
        {
            try
            {
                return JsonSerializer.Deserialize<Prefs>(File.ReadAllText(PathOf())) ?? new Prefs();
            }
            catch
            {
                return new Prefs();
            }
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(PathOf())!);
                File.WriteAllText(PathOf(), JsonSerializer.Serialize(this));
            }
            catch
            {
                // a missing preference file only means defaults next time
            }
        }

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

        public static bool OpenAtSignIn
        {
            get
            {
                using var k = Registry.CurrentUser.OpenSubKey(RunKey);
                return k?.GetValue("KankiVPN") != null;
            }
            set
            {
                using var k = Registry.CurrentUser.CreateSubKey(RunKey);
                if (value) k.SetValue("KankiVPN", "\"" + Environment.ProcessPath + "\" --tray");
                else k.DeleteValue("KankiVPN", false);
            }
        }
    }
}
