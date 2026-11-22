package network.clasher.events.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Atomic YAML writer that writes to a sibling temporary file before replacing the target file.
 */
public final class YamlAtomicStorage {
    private YamlAtomicStorage() {}

    public static boolean saveAtomically(YamlConfiguration yaml, File target, Logger logger, String failureMessage) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            logger.severe("Could not create data directory: " + parent.getAbsolutePath());
            return false;
        }
        File temp = new File(parent, target.getName() + ".tmp");
        try {
            yaml.save(temp);
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                try {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(atomicFailure);
                    throw fallbackFailure;
                }
            }
            return true;
        } catch (IOException ex) {
            logger.log(Level.SEVERE, failureMessage, ex);
            return false;
        }
    }
}
