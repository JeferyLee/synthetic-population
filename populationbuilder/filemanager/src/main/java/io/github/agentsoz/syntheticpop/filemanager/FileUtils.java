/**
 *
 */
package io.github.agentsoz.syntheticpop.filemanager;

import io.github.agentsoz.syntheticpop.util.LambdaCheckedException;
import io.github.agentsoz.syntheticpop.util.Log;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * @author Bhagya N. Wickramasinghe
 */
public class FileUtils {

    public static List<Path> find(Path startDir, String pattern) {
        Find finder = new Find(pattern);
        try {
            Files.walkFileTree(startDir, finder);
        } catch (IOException ex) {
            Log.error("When trying to walk file tree", ex);
        }
        List<Path> files = finder.getFilePaths();
        return files;
    }

    public static String getFileName(Path filename) {
        return filename.getFileName().toString().split("\\.(?=[^\\.]+$)")[0];
    }

    public static String getFileExtension(Path filename) {
        return filename.getFileName().toString().split("\\.(?=[^\\.]+$)")[1];
    }

    public static void delete(List<Path> filesToDelete) throws IOException {
        for (Path dir : filesToDelete) {
            Files.walk(dir, FileVisitOption.FOLLOW_LINKS)
                 .sorted(Comparator.reverseOrder())
                 .forEach(LambdaCheckedException.handlingConsumerWrapper(Files::deleteIfExists, IOException.class));

        }
    }

}
