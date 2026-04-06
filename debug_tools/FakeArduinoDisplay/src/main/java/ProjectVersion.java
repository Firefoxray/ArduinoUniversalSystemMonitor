import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.regex.Pattern;

final class ProjectVersion {
    private static final String UPDATE_SOURCE_FILE = ".last_update_source";
    private static final Pattern[] CODEX_BRANCH_PATTERNS = new Pattern[]{
            Pattern.compile("^codex/.*"),
            Pattern.compile("^codex-.*")
    };

    private ProjectVersion() {
    }

    static String loadVersion(Class<?> anchorClass) {
        Path versionFile = locateVersionFile(anchorClass);
        if (versionFile == null) {
            return "unknown version";
        }
        try {
            String version = normalizeVersion(Files.readString(versionFile, StandardCharsets.UTF_8).trim());
            if (!version.isEmpty()) {
                UpdateSource updateSource = detectUpdateSource(versionFile.getParent());
                if (updateSource == UpdateSource.CODEX && !version.toUpperCase(Locale.ROOT).contains("(CODEX-BRANCH)")) {
                    return version + " (CODEX-BRANCH)";
                }
                return version;
            }
        } catch (IOException ignored) {
        }
        return "unknown version";
    }

    private static Path locateVersionFile(Class<?> anchorClass) {
        for (Path start : candidateRoots(anchorClass)) {
            Path found = findRepoVersionFile(start);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Path[] candidateRoots(Class<?> anchorClass) {
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path classDir = null;
        try {
            classDir = Paths.get(anchorClass.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (URISyntaxException | NullPointerException ignored) {
        }
        if (classDir == null) {
            return new Path[]{userDir};
        }
        return new Path[]{userDir, classDir};
    }

    private static Path findRepoVersionFile(Path start) {
        Path cursor = Files.isDirectory(start) ? start : start.getParent();
        while (cursor != null) {
            Path versionFile = cursor.resolve("VERSION");
            if (Files.isRegularFile(versionFile)
                    && Files.isRegularFile(cursor.resolve("README.md"))
                    && (Files.isRegularFile(cursor.resolve("install.sh"))
                    || Files.isRegularFile(cursor.resolve("scripts/install.sh")))) {
                return versionFile;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static boolean isCodexBranchBuild(Path repoRoot) {
        Path headPath = repoRoot.resolve(".git").resolve("HEAD");
        if (!Files.isRegularFile(headPath)) {
            return false;
        }
        try {
            String head = Files.readString(headPath, StandardCharsets.UTF_8).trim();
            String branch = extractBranchName(head);
            if (branch == null || branch.isBlank()) {
                return false;
            }
            for (Pattern pattern : CODEX_BRANCH_PATTERNS) {
                if (pattern.matcher(branch).matches()) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static UpdateSource detectUpdateSource(Path repoRoot) {
        UpdateSource fromMarker = readUpdateSourceMarker(repoRoot);
        if (fromMarker != UpdateSource.UNKNOWN) {
            return fromMarker;
        }
        return isCodexBranchBuild(repoRoot) ? UpdateSource.CODEX : UpdateSource.UNKNOWN;
    }

    private static UpdateSource readUpdateSourceMarker(Path repoRoot) {
        Path markerPath = repoRoot.resolve(UPDATE_SOURCE_FILE);
        if (!Files.isRegularFile(markerPath)) {
            return UpdateSource.UNKNOWN;
        }
        try {
            String marker = Files.readString(markerPath, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if ("main".equals(marker)) {
                return UpdateSource.MAIN;
            }
            if ("codex".equals(marker)) {
                return UpdateSource.CODEX;
            }
        } catch (IOException ignored) {
        }
        return UpdateSource.UNKNOWN;
    }

    private static String normalizeVersion(String version) {
        String text = version == null ? "" : version.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (text.startsWith("V") && text.length() >= 2 && Character.isDigit(text.charAt(1))) {
            return "v" + text.substring(1);
        }
        return text;
    }

    private static String extractBranchName(String head) {
        String prefix = "ref: refs/heads/";
        if (head == null || !head.startsWith(prefix)) {
            return null;
        }
        return head.substring(prefix.length()).trim();
    }

    private enum UpdateSource {
        MAIN,
        CODEX,
        UNKNOWN
    }
}
