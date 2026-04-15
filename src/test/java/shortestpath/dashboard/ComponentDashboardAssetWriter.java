package shortestpath.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import shortestpath.Util;

public class ComponentDashboardAssetWriter {
    private static final String[] ASSETS = {
        "/component-dashboard/index.html",
        "/component-dashboard/app.js",
        "/component-dashboard/styles.css"
    };

    public void writeAssets(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);
        for (String asset : ASSETS) {
            try (InputStream in = ComponentDashboardAssetWriter.class.getResourceAsStream(asset)) {
                if (in == null) {
                    throw new IOException("Missing asset resource: " + asset);
                }
                Path destination = outputDirectory.resolve(asset.substring(asset.lastIndexOf('/') + 1));
                Files.write(destination, Util.readAllBytes(in));
            }
        }
    }
}
