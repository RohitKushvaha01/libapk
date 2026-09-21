import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.io.BlockReader;
import com.reandroid.arsc.model.ResourceEntry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts the Android framework attribute name -> resource id table (`android:` namespace)
 * from a platform `android.jar`, and writes it as a plain text resource that libapk ships.
 *
 * libapk needs these ids to emit correctly namespaced attributes in a binary manifest without
 * aapt2. ARSCLib deliberately does not bundle a framework table, so we generate one from the
 * platform the user is targeting. The ids are stable AOSP public resource ids.
 *
 * Usage: GenerateAndroidAttributeIds &lt;android.jar&gt; &lt;output.txt&gt;
 */
public final class GenerateAndroidAttributeIds {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: GenerateAndroidAttributeIds <android.jar> <output.txt>");
            System.exit(2);
        }
        File androidJar = new File(args[0]);
        File output = new File(args[1]);
        if (!androidJar.isFile()) {
            throw new IllegalArgumentException("not a file: " + androidJar);
        }

        File extracted = File.createTempFile("libapk-android-res-", ".arsc");
        try {
            try (ZipFile zip = new ZipFile(androidJar)) {
                ZipEntry entry = zip.getEntry("resources.arsc");
                if (entry == null) {
                    throw new IllegalStateException("resources.arsc not found inside " + androidJar);
                }
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream out = new FileOutputStream(extracted)) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }

            TableBlock table = new TableBlock();
            try (BlockReader reader = new BlockReader(extracted)) {
                table.readBytes(reader);
            }

            TreeMap<String, Integer> attributes = new TreeMap<>();
            for (PackageBlock pkg : table) {
                Iterator<ResourceEntry> entries = pkg.getResources("attr");
                while (entries.hasNext()) {
                    ResourceEntry entry = entries.next();
                    String name = entry.getName();
                    int id = entry.getResourceId();
                    if (name != null && !name.isEmpty() && id != 0) {
                        attributes.putIfAbsent(name, id);
                    }
                }
            }
            if (attributes.isEmpty()) {
                throw new IllegalStateException("no attr resources found in " + androidJar);
            }

            File parent = output.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8))) {
                writer.println("# Android framework attribute ids for the `android:` namespace.");
                writer.println("# Extracted from " + androidJar.getName() + " (resources.arsc).");
                writer.println("# Regenerate with tools/generate-android-attribute-ids/generate.sh");
                writer.println("# Format: <attribute-name>=0x<8 hex digits>");
                for (Map.Entry<String, Integer> entry : attributes.entrySet()) {
                    writer.printf("%s=0x%08x%n", entry.getKey(), entry.getValue());
                }
            }
            System.out.println("wrote " + attributes.size() + " attributes to " + output);
        } finally {
            extracted.delete();
        }
    }
}
