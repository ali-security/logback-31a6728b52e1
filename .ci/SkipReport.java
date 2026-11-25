import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Prints a per-test report of every skipped test found in the surefire XML reports of
 * the reactor modules, with the reason (surefire message, else the @Disabled/@Ignore/assume
 * line in the test source), followed by per-module and total counts.
 *
 * Usage: java .ci/SkipReport.java [repoRoot]
 */
public class SkipReport {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        List<Path> reports;
        try (Stream<Path> s = Files.walk(root, 6)) {
            reports = s.filter(p -> p.getFileName().toString().startsWith("TEST-")
                    && p.getFileName().toString().endsWith(".xml")
                    && p.getParent() != null
                    && p.getParent().getFileName().toString().equals("surefire-reports"))
                    .sorted().collect(Collectors.toList());
        }
        Map<String, int[]> perModule = new TreeMap<>();
        List<String> skipLines = new ArrayList<>();
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        for (Path report : reports) {
            Path moduleDir = report.getParent().getParent().getParent();
            String module = root.relativize(moduleDir).toString();
            int[] c = perModule.computeIfAbsent(module, k -> new int[4]);
            Document doc = f.newDocumentBuilder().parse(report.toFile());
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                c[0]++;
                if (tc.getElementsByTagName("failure").getLength() > 0) c[1]++;
                if (tc.getElementsByTagName("error").getLength() > 0) c[2]++;
                NodeList sk = tc.getElementsByTagName("skipped");
                if (sk.getLength() > 0) {
                    c[3]++;
                    String cls = tc.getAttribute("classname");
                    String method = tc.getAttribute("name");
                    String msg = ((Element) sk.item(0)).getAttribute("message");
                    String reason = (msg == null || msg.isBlank()) ? sourceReason(moduleDir, cls, method) : msg.trim();
                    skipLines.add("SKIPPED " + module + " " + cls + "#" + method + ": " + reason);
                }
            }
        }
        System.out.println("==================== SKIPPED TEST REPORT ====================");
        skipLines.forEach(System.out::println);
        int[] t = new int[4];
        for (Map.Entry<String, int[]> e : perModule.entrySet()) {
            int[] c = e.getValue();
            System.out.printf("MODULE %s: tests=%d passed=%d failures=%d errors=%d skipped=%d%n",
                    e.getKey(), c[0], c[0] - c[1] - c[2] - c[3], c[1], c[2], c[3]);
            for (int k = 0; k < 4; k++) t[k] += c[k];
        }
        System.out.printf("TOTAL: tests=%d passed=%d failures=%d errors=%d skipped=%d%n",
                t[0], t[0] - t[1] - t[2] - t[3], t[1], t[2], t[3]);
    }

    /** Find why a test is skipped by reading its source: method/class-level @Disabled/@Ignore, or an assume call. */
    static String sourceReason(Path moduleDir, String cls, String method) {
        String outer = cls.contains("$") ? cls.substring(0, cls.indexOf('$')) : cls;
        Path src = moduleDir.resolve("src/test/java").resolve(outer.replace('.', '/') + ".java");
        if (!Files.exists(src)) {
            return "skipped by the test framework (source not found for " + cls + ")";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(src, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "skipped by the test framework";
        }
        String m = method.replaceAll("[\\(\\[].*$", "");
        int decl = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches(".*\\b(void|public|protected|private)\\b.*\\b" + java.util.regex.Pattern.quote(m) + "\\s*\\(.*")) {
                decl = i;
                break;
            }
        }
        if (decl >= 0) {
            // annotations directly above the method
            for (int i = decl - 1; i >= 0 && i >= decl - 8; i--) {
                String l = lines.get(i).trim();
                if (l.startsWith("@Disabled") || l.startsWith("@Ignore")) {
                    return "disabled upstream in source: " + l + " (" + src.getFileName() + ":" + (i + 1) + ")";
                }
                if (l.isEmpty() || l.endsWith("}") || l.endsWith(";")) break;
            }
            // assume* calls inside the method body
            int depth = 0;
            boolean started = false;
            for (int i = decl; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("assume") || l.contains("Assumptions.")) {
                    return "assumption not met: " + l.trim() + " (" + src.getFileName() + ":" + (i + 1) + ")";
                }
                for (char ch : l.toCharArray()) {
                    if (ch == '{') { depth++; started = true; }
                    if (ch == '}') depth--;
                }
                if (started && depth <= 0) break;
            }
        }
        // class-level annotation
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.startsWith("@Disabled") || l.startsWith("@Ignore")) {
                for (int j = i + 1; j < lines.size() && j <= i + 6; j++) {
                    if (lines.get(j).contains(" class ")) {
                        return "whole test class disabled upstream: " + l + " (" + src.getFileName() + ":" + (i + 1) + ")";
                    }
                }
            }
            if (l.contains("@DisabledOn") || l.contains("@EnabledOn") || l.contains("@EnabledIf") || l.contains("@DisabledIf")) {
                return "conditionally disabled upstream: " + l + " (" + src.getFileName() + ":" + (i + 1) + ")";
            }
        }
        return "skipped by the test framework (no annotation/assumption found in " + src.getFileName() + ")";
    }
}
