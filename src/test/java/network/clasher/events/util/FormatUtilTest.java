package network.clasher.events.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FormatUtilTest {

    @Test
    void formatsDurationsCleanly() {
        assertEquals("0s", FormatUtil.formatDuration(0));
        assertEquals("45s", FormatUtil.formatDuration(45));
        assertEquals("2m 00s", FormatUtil.formatDuration(120));
        assertEquals("1h 01m 05s", FormatUtil.formatDuration(3665));
    }

    @Test
    void parsesBothMiniMessageAndLegacyStrings() {
        String mini = PlainTextComponentSerializer.plainText().serialize(FormatUtil.parseFlexible("<gold><bold>CLASHER</bold></gold>"));
        String legacy = PlainTextComponentSerializer.plainText().serialize(FormatUtil.parseFlexible("&6&lCLASHER"));
        assertEquals("CLASHER", mini);
        assertEquals("CLASHER", legacy);
    }

    @Test
    void escapesMiniMessageTags() {
        assertEquals("\\<red>Alex", FormatUtil.escape("<red>Alex"));
    }
}
