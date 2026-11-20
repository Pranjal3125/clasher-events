package network.clasher.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesTargetOnlyAfterTemporaryContentIsWritten() throws IOException {
        Path target = temporaryDirectory.resolve("state.yml");
        Files.writeString(target, "old-state");

        AtomicFileWriter.write(target.toFile(), temporary -> Files.writeString(temporary.toPath(), "new-state"));

        assertEquals("new-state", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("state.yml.tmp")));
    }

    @Test
    void failedWritePreservesExistingTargetAndCleansTemporaryFile() throws IOException {
        Path target = temporaryDirectory.resolve("state.yml");
        Files.writeString(target, "known-good-state");

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target.toFile(), temporary -> {
            Files.writeString(temporary.toPath(), "partial-state");
            throw new IOException("simulated disk/write failure");
        }));

        assertEquals("known-good-state", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("state.yml.tmp")));
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path target = temporaryDirectory.resolve("nested/data/state.yml");

        AtomicFileWriter.write(target.toFile(), temporary -> Files.writeString(temporary.toPath(), "saved"));

        assertEquals("saved", Files.readString(target));
    }
}
