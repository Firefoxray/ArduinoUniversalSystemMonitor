import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class ProjectVersion {
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

}
