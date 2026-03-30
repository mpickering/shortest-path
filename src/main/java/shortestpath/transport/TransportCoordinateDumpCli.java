package shortestpath.transport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class TransportCoordinateDumpCli {
    public static void main(String[] args) throws IOException {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, PrintStream stdout, PrintStream stderr) throws IOException {
        boolean pretty = false;
        Path output = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--pretty".equals(arg)) {
                pretty = true;
                continue;
            }
            if ("--output".equals(arg)) {
                if (i + 1 >= args.length) {
                    stderr.println("Missing value for --output");
                    return 1;
                }
                output = Path.of(args[++i]);
                continue;
            }

            stderr.println("Unknown argument: " + arg);
            return 1;
        }

        List<TransportCoordinateItem> items = new TransportCoordinateExporter().exportAllFromResources();
        String json = TransportCoordinateExporter.toJson(items, pretty);

        if (output != null) {
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            Files.writeString(output, json, StandardCharsets.UTF_8);
        } else {
            stdout.print(json);
        }
        return 0;
    }
}
