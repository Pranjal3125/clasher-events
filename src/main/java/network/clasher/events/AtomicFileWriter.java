package network.clasher.events;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Replaces a persisted file only after its complete temporary copy is written.
 */
final class AtomicFileWriter {
    @FunctionalInterface
    interface ContentWriter {
        void write(File temporaryFile) throws IOException;
    }

    private AtomicFileWriter() {
    }

    static void write(File target, ContentWriter contentWriter) throws IOException {
        if (target == null || contentWriter == null) {
            throw new IllegalArgumentException("Target and content writer are required");
        }
        File absoluteTarget = target.getAbsoluteFile();
        File parent = absoluteTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent);
        }
        File temporary = new File(parent, absoluteTarget.getName() + ".tmp");
        try {
            contentWriter.write(temporary);
            try {
                Files.move(temporary.toPath(), absoluteTarget.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), absoluteTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            if (temporary.exists() && !temporary.delete()) {
                ex.addSuppressed(new IOException("Could not remove temporary file: " + temporary));
            }
            throw ex;
        }
    }
}
