package com.chimeraant.terminal.session;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Creates the on-device Linux-like layout each sandbox runs inside and builds
 * the environment handed to the shell.
 *
 * Every sandbox gets its own {@code HOME}, {@code TMPDIR} and {@code PREFIX}
 * under the app's private storage. Because the paths are per-sandbox, deleting
 * or corrupting one sandbox cannot affect another, and no sandbox can touch
 * another's files without an explicit bind.
 */
public class EnvironmentBuilder {

    public static final String TERMINAL_APP = "chimera";

    private final Context context;
    private final File filesDir;

    public EnvironmentBuilder(Context context) {
        this.context = context.getApplicationContext();
        this.filesDir = this.context.getFilesDir();
    }

    public File getSandboxRoot(String sandboxId) {
        return new File(filesDir, "sandboxes/" + sandboxId);
    }

    public File getHome(String sandboxId) {
        return new File(getSandboxRoot(sandboxId), "home");
    }

    public File getPrefix(String sandboxId) {
        return new File(getSandboxRoot(sandboxId), "usr");
    }

    public File getTmp(String sandboxId) {
        return new File(getSandboxRoot(sandboxId), "tmp");
    }

    /** Ensure the directory skeleton and shell startup files exist. */
    public void prepare(String sandboxId, String displayName) throws IOException {
        File root = getSandboxRoot(sandboxId);
        mkdirs(root);

        File home = getHome(sandboxId);
        File prefix = getPrefix(sandboxId);
        File tmp = getTmp(sandboxId);

        File bin = new File(prefix, "bin");
        mkdirs(home);
        mkdirs(bin);
        mkdirs(new File(prefix, "etc"));
        mkdirs(new File(prefix, "lib"));
        mkdirs(new File(prefix, "var/tmp"));
        mkdirs(tmp);
        mkdirs(new File(root, "workspace"));

        // Seed a real, executable command in $PREFIX/bin. This proves the
        // PATH works and gives new users something that answers.
        File help = new File(bin, "help");
        writeIfAbsent(help, HELP_SCRIPT);
        help.setExecutable(true, false);

        writeIfAbsent(new File(home, ".mkshrc"), mkshrc(displayName, prefix));
        writeIfAbsent(new File(home, ".profile"), profile(prefix));
        writeIfAbsent(new File(home, ".bashrc"), profile(prefix));
        writeIfAbsent(new File(root, "motd.txt"), MOTD);
    }

    /** The bundled help command, written to $PREFIX/bin/help. */
    private static final String HELP_SCRIPT =
            "#!/system/bin/sh\n"
            + "cat <<'CHIMERA_HELP'\n"
            + "\n"
            + "Chimera Terminal - built-in commands\n"
            + "\n"
            + "  help          show this text\n"
            + "  cd <dir>      change directory\n"
            + "  pwd           print the current directory\n"
            + "  ls [dir]      list files\n"
            + "  cat <file>    print a file\n"
            + "  echo <text>   print text\n"
            + "  clear         clear the screen\n"
            + "  exit          close this sandbox\n"
            + "\n"
            + "Android commands also work, for example:\n"
            + "  getprop                list system properties\n"
            + "  dumpsys battery        battery details\n"
            + "  am start -a android.intent.action.VIEW -d https://example.com\n"
            + "  pm list packages       list installed apps\n"
            + "  settings get global airplane_mode_on\n"
            + "\n"
            + "To install more commands, open the drawer and choose Termux userland.\n"
            + "That adds pkg and apt, so you can install git, python, ssh and more.\n"
            + "\n"
            + "Swipe down on the screen to scroll back. Long press to select text.\n"
            + "CHIMERA_HELP\n";

    /** Build the environment map for {@code PtyProcess.spawn}. */
    public Map<String, String> buildEnvironment(String sandboxId, boolean rootMode) {
        String home = getHome(sandboxId).getAbsolutePath();
        String prefix = getPrefix(sandboxId).getAbsolutePath();
        String tmp = getTmp(sandboxId).getAbsolutePath();

        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home);
        env.put("PREFIX", prefix);
        env.put("TMPDIR", tmp);
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("TERMUX_VERSION", "1.0");
        env.put("CHIMERA_TERMINAL", "1");
        env.put("CHIMERA_SANDBOX", sandboxId);
        env.put("PATH", prefix + "/bin:/system/bin:/system/xbin:/vendor/bin:"
                + "/product/bin:/sbin:/su/bin");
        env.put("SHELL", "/system/bin/sh");
        env.put("USER", rootMode ? "root" : "shell");
        env.put("LOGNAME", rootMode ? "root" : "shell");
        env.put("LD_LIBRARY_PATH", "/system/lib64:/system/lib");
        env.put("ANDROID_DATA", context.getApplicationInfo().dataDir);
        env.put("ANDROID_ROOT", "/system");
        env.put("EXTERNAL_STORAGE", "/sdcard");
        env.put("PS1", rootMode
                ? "\\u@chimera \\W \\$ "
                : "\\u@chimera \\W \\$ ");
        env.put("ENV", home + "/.mkshrc");
        env.put("TIMEFMT", "%J %P %*E %M %C");

        // Preserve a usable PATH if a caller supplied more entries.
        String inherited = System.getenv("PATH");
        if (inherited != null && !inherited.isEmpty()) {
            env.put("PATH", env.get("PATH") + ":" + inherited);
        }
        return env;
    }

    private static String mkshrc(String displayName, File prefix) {
        return "# Chimera Terminal sandbox: " + displayName + "\n"
                + "export PS1='\\u@chimera \\W \\$ '\n"
                + "export PATH='" + prefix.getAbsolutePath() + "/bin:/system/bin:/system/xbin:$PATH'\n"
                + "export EDITOR=vi\n"
                + "export PAGER=less\n"
                + "alias ls='ls --color=auto 2>/dev/null || ls'\n"
                + "alias ll='ls -la'\n"
                + "alias la='ls -A'\n"
                + "alias clear='printf \"\\033[2J\\033[H\"'\n"
                + "case \"$-\" in *i*) echo; cat \"$(dirname \"$HOME\")/motd.txt\" 2>/dev/null;; esac\n";
    }

    private static String profile(File prefix) {
        // Prepend our bin directory, then the Android system locations, so
        // commands in $PREFIX/bin are found and system tools still resolve.
        return "# Chimera Terminal profile\n"
                + "export PATH=\"" + prefix.getAbsolutePath()
                + "/bin:/system/bin:/system/xbin:/vendor/bin:/product/bin:$PATH\"\n"
                + "export PREFIX='" + prefix.getAbsolutePath() + "'\n"
                + "[ -f \"$HOME/.mkshrc\" ] && . \"$HOME/.mkshrc\"\n";
    }

    private static final String MOTD =
            "\u001B[38;2;127;212;255m"
            + "  Chimera Terminal\n"
            + "\u001B[0m"
            + "  Type help to see what you can run.\n"
            + "  Type exit to close this sandbox.\n";

    private void mkdirs(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create directory: " + dir.getAbsolutePath());
        }
    }

    private void writeIfAbsent(File file, String content) throws IOException {
        if (file.exists() && file.length() > 0) return;
        File parent = file.getParentFile();
        if (parent != null) mkdirs(parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A fresh sandbox id that is filesystem and shell safe. */
    public static String newSandboxId() {
        return "sbx-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    public Map<String, String> defaultEnvironment(String sandboxId) {
        return buildEnvironment(sandboxId, false);
    }

    public Map<String, String> emptyEnvironment() {
        return new HashMap<>();
    }
}
