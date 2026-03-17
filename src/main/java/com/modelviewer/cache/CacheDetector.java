package com.modelviewer.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

/**
 * Automatically locates valid OSRS cache directories on the host machine.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Background — where the cache actually lives
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * When RuneLite is used as the OSRS launcher it redirects the game cache from
 * the old Jagex default into its own user-data directory:
 *
 *   Old Jagex default : %USERPROFILE%\jagexcache\oldschool\LIVE
 *   New Jagex default : %USERPROFILE%\.boneyard\jagexcache\oldschool\LIVE  ← renamed by Jagex
 *   RuneLite redirect : %USERPROFILE%\.runelite\jagexcache\oldschool\LIVE
 *
 * On macOS and Linux the equivalents are ~/jagexcache/… and ~/.runelite/jagexcache/…
 *
 * On top of the standard locations, several additional path variants are checked:
 *   - RuneLite installed via Snap  : ~/snap/runelite/current/.runelite/jagexcache/oldschool/LIVE
 *   - RuneLite installed via Flatpak: ~/.var/app/net.runelite.RuneLite/.runelite/jagexcache/oldschool/LIVE
 *   - The new Jagex Launcher (2023+): %LOCALAPPDATA%\Jagex\Launcher\Saved Games\*\jagexcache\oldschool\LIVE
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Settings-file parsing
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * RuneLite stores its user configuration in:
 *   %USERPROFILE%\.runelite\settings.properties    (global)
 *   %USERPROFILE%\.runelite\profiles2\*.properties (per-profile)
 *
 * Both are standard Java Properties files.  If any entry contains a key that
 * looks like a cache-path override (case-insensitive match on "jagex", "cache",
 * or "directory"), the corresponding value is added as an additional candidate.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Validation
 * ──────────────────────────────────────────────────────────────────────────────
 *
 * A directory is considered a valid cache if it contains BOTH:
 *   main_file_cache.dat2    — main data file
 *   main_file_cache.idx255  — master index (reference tables)
 */
public final class CacheDetector {

    private static final Logger log = LoggerFactory.getLogger(CacheDetector.class);

    /** Relative sub-path appended to a RuneLite/Jagex root to reach the cache. */
    private static final String OSRS_CACHE_SUBPATH = "jagexcache/oldschool/LIVE";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Describes one detected cache directory candidate.
     *
     * @param directory   the candidate directory on disk
     * @param source      human-readable description of where it was found
     * @param fromRuneLite whether this candidate was discovered via a RuneLite path
     */
    public record CacheCandidate(File directory, String source, boolean fromRuneLite) {

        /**
         * Returns {@code true} if the directory exists and contains the two files
         * that are always present in a valid OSRS cache:
         *   main_file_cache.dat2  and  main_file_cache.idx255
         */
        public boolean isValid() {
            if (!directory.isDirectory()) return false;
            return new File(directory, "main_file_cache.dat2").exists()
                && new File(directory, "main_file_cache.idx255").exists();
        }

        @Override
        public String toString() {
            return String.format("[%s] %s  (%s)",
                    fromRuneLite ? "RuneLite" : "Jagex",
                    directory.getAbsolutePath(),
                    source);
        }
    }

    private CacheDetector() {}

    /**
     * Returns all detected valid cache candidates in priority order.
     *
     * Duplicates (same canonical path) are automatically removed.
     * Only directories that pass {@link CacheCandidate#isValid()} are returned.
     */
    public static List<CacheCandidate> detectAll() {
        List<CacheCandidate> raw = gatherCandidates();
        List<CacheCandidate> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (CacheCandidate c : raw) {
            String canon = canonical(c.directory());
            if (seen.add(canon) && c.isValid()) {
                valid.add(c);
                log.info("Valid OSRS cache detected: {}", c);
            }
        }

        if (valid.isEmpty()) {
            log.info("No valid OSRS cache found in any standard location");
        }
        return valid;
    }

    /**
     * Returns the single best candidate (highest-priority valid cache), or
     * {@code null} if nothing could be found.
     */
    public static CacheCandidate detectBest() {
        return detectAll().stream().findFirst().orElse(null);
    }

    /**
     * Returns all probed candidate directories — both valid and invalid —
     * deduplicated by canonical path, in priority order.
     *
     * This is used by the detection dialog to show the user which paths were
     * checked and why each one was or was not auto-selected.
     */
    public static List<CacheCandidate> detectAllCandidates() {
        List<CacheCandidate> raw = gatherCandidates();
        List<CacheCandidate> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CacheCandidate c : raw) {
            if (seen.add(canonical(c.directory()))) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Returns {@code true} if a RuneLite user-data directory (or executable)
     * is present on this machine, regardless of whether a cache was found.
     */
    public static boolean isRuneLiteInstalled() {
        // Check the user-data directory (exists after first RuneLite launch)
        File userDir = getRuneLiteUserDir();
        if (userDir != null && userDir.isDirectory()) return true;

        // Also check the installation directory for the executable
        for (File exe : getRuneLiteExecutablePaths()) {
            if (exe.exists()) return true;
        }
        return false;
    }

    // ── Candidate collection ──────────────────────────────────────────────────

    /**
     * Builds the ordered list of all candidate directories to probe.
     * Does NOT filter for validity — {@link #detectAll} does that.
     */
    private static List<CacheCandidate> gatherCandidates() {
        List<CacheCandidate> list = new ArrayList<>();

        // ── 1. RuneLite user-data directory (highest priority) ────────────────
        // RuneLite redirects the OSRS cache from the Jagex default into its own
        // user-data tree: ~/.runelite/jagexcache/oldschool/LIVE
        File rlUserDir = getRuneLiteUserDir();
        if (rlUserDir != null) {
            list.add(candidate(new File(rlUserDir, OSRS_CACHE_SUBPATH),
                    "RuneLite (user data directory)", true));

            // Also scan RuneLite settings files for any non-default cache path
            collectRuneLiteConfiguredPaths(rlUserDir, list);
        }

        // ── 2. New Jagex default (.boneyard, 2024+) ───────────────────────────
        // Jagex renamed their cache root from jagexcache to .boneyard
        list.add(candidate(getBoneyardCacheDir(), "Jagex (.boneyard)", false));

        // ── 3. Standard Jagex cache (used by official Jagex Launcher / Steam) ─
        list.add(candidate(getJagexDefaultCacheDir(),
                "OSRS official / Jagex Launcher", false));

        // ── 3. New Jagex Launcher (2023+) ──────────────────────────────────────
        // Jagex Launcher ≥ 2.0 can store the cache under %LOCALAPPDATA%\Jagex
        for (File dir : getJagexLauncherPaths()) {
            list.add(candidate(dir, "Jagex Launcher (new)", false));
        }

        // ── 4. Linux package-manager variants ─────────────────────────────────
        if (isLinux()) {
            // Snap (Ubuntu snap store)
            list.add(candidate(
                new File(home(), "snap/runelite/current/.runelite/" + OSRS_CACHE_SUBPATH),
                "RuneLite (Snap)", true));

            // Flatpak (Flathub)
            list.add(candidate(
                new File(home(), ".var/app/net.runelite.RuneLite/.runelite/" + OSRS_CACHE_SUBPATH),
                "RuneLite (Flatpak)", true));
        }

        return list;
    }

    /**
     * Reads RuneLite's settings.properties and per-profile .properties files and
     * adds any path values that look like a custom jagexcache location.
     *
     * RuneLite doesn't offer a first-class "change cache directory" option in its
     * UI, but power users or RSPS operators sometimes launch it with a custom path.
     * Relevant property keys contain "jagex", "cache", or "directory" (case-insensitive).
     */
    private static void collectRuneLiteConfiguredPaths(File rlUserDir,
                                                        List<CacheCandidate> out) {
        // Global settings
        File globalSettings = new File(rlUserDir, "settings.properties");
        parsePropertiesFile(globalSettings, out, "RuneLite settings.properties");

        // Per-profile settings (profiles2/*.properties)
        File profiles2 = new File(rlUserDir, "profiles2");
        if (profiles2.isDirectory()) {
            File[] profileFiles = profiles2.listFiles(
                    (dir, name) -> name.endsWith(".properties") && !name.equals("profiles.json"));
            if (profileFiles != null) {
                for (File pf : profileFiles) {
                    String profileName = pf.getName().replaceFirst("\\.properties$", "");
                    // Trim the numeric suffix that RuneLite appends (e.g. "default-274228040798800")
                    profileName = profileName.replaceAll("-\\d{10,}$", "");
                    parsePropertiesFile(pf, out, "RuneLite profile \"" + profileName + "\"");
                }
            }
        }
    }

    /**
     * Parses a Java Properties file and adds any value that looks like an
     * absolute path to a jagexcache directory.
     */
    private static void parsePropertiesFile(File file, List<CacheCandidate> out, String source) {
        if (file == null || !file.exists()) return;
        Properties props = new Properties();
        try (Reader r = new FileReader(file)) {
            props.load(r);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", file, e.getMessage());
            return;
        }

        for (String key : props.stringPropertyNames()) {
            String kl = key.toLowerCase();
            // Only look at keys that sound cache-path-related
            if (!kl.contains("jagex") && !kl.contains("cachedirectory") && !kl.contains("cachedir")) {
                continue;
            }
            String value = props.getProperty(key, "").trim();
            if (value.isEmpty()) continue;

            File path = new File(value);
            if (path.isAbsolute()) {
                log.debug("Found configured cache path in {}: key={} val={}", source, key, value);
                out.add(candidate(path, source + " (key: " + key + ")", true));
            }
        }
    }

    // ── OS-specific path helpers ──────────────────────────────────────────────

    /**
     * Returns the RuneLite user-data directory for the current OS.
     *
     * <pre>
     *   Windows : %USERPROFILE%\.runelite\
     *   macOS   : ~/Library/Application Support/RuneLite/
     *   Linux   : ~/.runelite/
     * </pre>
     */
    public static File getRuneLiteUserDir() {
        if (isWindows()) {
            // Prefer %USERPROFILE% over user.home because they can differ when
            // running as a service or in some CI environments
            String profile = System.getenv("USERPROFILE");
            String base = (profile != null && !profile.isEmpty()) ? profile : home().getAbsolutePath();
            return new File(base, ".runelite");
        } else if (isMac()) {
            return new File(home(), "Library/Application Support/RuneLite");
        } else {
            return new File(home(), ".runelite");
        }
    }

    /**
     * Returns the new Jagex cache directory (.boneyard, introduced ~2024).
     *
     * <pre>
     *   Windows : %USERPROFILE%\.boneyard\jagexcache\oldschool\LIVE
     *   macOS   : ~/.boneyard/jagexcache/oldschool/LIVE
     *   Linux   : ~/.boneyard/jagexcache/oldschool/LIVE
     * </pre>
     */
    public static File getBoneyardCacheDir() {
        if (isWindows()) {
            String profile = System.getenv("USERPROFILE");
            String base = (profile != null && !profile.isEmpty()) ? profile : home().getAbsolutePath();
            return new File(base, ".boneyard/" + OSRS_CACHE_SUBPATH);
        }
        return new File(home(), ".boneyard/" + OSRS_CACHE_SUBPATH);
    }

    /**
     * Returns the standard Jagex default cache directory.
     *
     * <pre>
     *   Windows : %USERPROFILE%\jagexcache\oldschool\LIVE
     *   macOS   : ~/jagexcache/oldschool/LIVE
     *   Linux   : ~/jagexcache/oldschool/LIVE
     * </pre>
     */
    public static File getJagexDefaultCacheDir() {
        if (isWindows()) {
            String profile = System.getenv("USERPROFILE");
            String base = (profile != null && !profile.isEmpty()) ? profile : home().getAbsolutePath();
            return new File(base, OSRS_CACHE_SUBPATH);
        }
        return new File(home(), OSRS_CACHE_SUBPATH);
    }

    /**
     * Returns known RuneLite executable locations.  Used only to detect
     * whether RuneLite is installed — not needed for cache path resolution.
     */
    private static List<File> getRuneLiteExecutablePaths() {
        List<File> paths = new ArrayList<>();
        if (isWindows()) {
            String localApp = System.getenv("LOCALAPPDATA");
            if (localApp != null) {
                paths.add(new File(localApp, "RuneLite/RuneLite.exe"));
                paths.add(new File(localApp, "Programs/RuneLite/RuneLite.exe"));
            }
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                paths.add(new File(appData, "RuneLite/RuneLite.exe"));
            }
        } else if (isMac()) {
            paths.add(new File("/Applications/RuneLite.app"));
        } else {
            paths.add(new File(home(), ".local/share/RuneLite/RuneLite"));
            paths.add(new File("/usr/local/bin/runelite"));
        }
        return paths;
    }

    /**
     * Returns candidate paths for the new Jagex Launcher (2023+).
     *
     * The new launcher installs to %LOCALAPPDATA%\Jagex\Launcher and may store
     * account-specific caches under a Saved Games sub-tree.
     */
    private static List<File> getJagexLauncherPaths() {
        List<File> paths = new ArrayList<>();
        if (isWindows()) {
            String localApp = System.getenv("LOCALAPPDATA");
            if (localApp != null) {
                // Direct cache under the launcher
                File launcherCache = new File(localApp, "Jagex/Launcher/" + OSRS_CACHE_SUBPATH);
                paths.add(launcherCache);

                // Saved Games style sub-directories (account ID folders)
                File savedGames = new File(localApp, "Jagex/Launcher/Saved Games");
                if (savedGames.isDirectory()) {
                    File[] accounts = savedGames.listFiles(File::isDirectory);
                    if (accounts != null) {
                        for (File acc : accounts) {
                            paths.add(new File(acc, OSRS_CACHE_SUBPATH));
                        }
                    }
                }
            }
        }
        return paths;
    }

    // ── Utility helpers ───────────────────────────────────────────────────────

    private static CacheCandidate candidate(File dir, String source, boolean fromRuneLite) {
        return new CacheCandidate(dir, source, fromRuneLite);
    }

    private static File home() {
        return new File(System.getProperty("user.home"));
    }

    private static String canonical(File f) {
        try { return f.getCanonicalPath(); }
        catch (IOException e) { return f.getAbsolutePath(); }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isLinux() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return !isWindows() && !isMac() && (os.contains("nix") || os.contains("nux") || os.contains("linux"));
    }
}
