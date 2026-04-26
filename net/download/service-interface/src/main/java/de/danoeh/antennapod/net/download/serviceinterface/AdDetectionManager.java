package de.danoeh.antennapod.net.download.serviceinterface;

import android.content.Context;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public abstract class AdDetectionManager {
    private static AdDetectionManager instance;

    public static AdDetectionManager getInstance() {
        return instance;
    }

    public static void setInstance(AdDetectionManager instance) {
        AdDetectionManager.instance = instance;
    }

    public abstract void enqueueAdDetection(Context context, FeedItem item);

    public static File adTimestampsFileFor(Context context, FeedMedia media) {
        File dir = new File(context.getCacheDir(), "adtimestamps");
        dir.mkdirs();
        return new File(dir, media.getId() + ".adtimestamps");
    }

    public static boolean isAdDetectionComplete(Context context, FeedMedia media) {
        File file = adTimestampsFileFor(context, media);
        if (!file.exists()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[200];
            int read = fis.read(buf, 0, buf.length);
            String head = new String(buf, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            return head.contains("\"complete\"");
        } catch (Exception e) {
            return false;
        }
    }
}
