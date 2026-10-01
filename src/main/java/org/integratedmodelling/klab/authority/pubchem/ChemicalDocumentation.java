package org.integratedmodelling.klab.authority.pubchem;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.integratedmodelling.klab.api.services.runtime.Notification;

/** Content-addressed documentation and real GIF bytes converted from documented PubChem PNGs. */
final class ChemicalDocumentation {
  record Result(Map<String, URL> urls, String description, List<Notification> notifications) {}

  static Result build(String id, String label, JsonNode metadata, List<String> parents,
      PubChemClient pubchem, String cid, String jsonUrl, Path directory, boolean gif) {
    var urls = new LinkedHashMap<String, URL>();
    var notifications = new ArrayList<Notification>();
    String page = cid == null ? "https://www.ebi.ac.uk/chebi/searchId.do?chebiId=" + ChemicalHttpClient.encode(id)
        : "https://pubchem.ncbi.nlm.nih.gov/compound/" + cid;
    urls.put("text/html", url(page));
    urls.put("application/json", url(jsonUrl));
    var text = new StringBuilder("# ").append(escape(label)).append("\n\n")
        .append("- **Identifier**: ").append(escape(id)).append("\n")
        .append("- **Provider**: PubChem with ChEBI through EMBL-EBI OLS4\n");
    if (!parents.isEmpty()) text.append("- **Direct structural parents**: ").append(escape(String.join(", ", parents))).append('\n');
    text.append("\n[Source record](<").append(page).append(">)\n\n## Source metadata\n\n");
    metadata.fields().forEachRemaining(field -> {
      // OLS navigation links are neither chemical metadata nor safe Markdown URLs.
      if (!field.getKey().equals("_links") && !field.getValue().isNull())
        text.append("- **").append(escape(field.getKey())).append("**: ")
            .append(escape(field.getValue().isTextual() ? field.getValue().asText() : field.getValue().toString())).append('\n');
    });
    if (cid != null) {
      urls.put("image/png", url(pubchem.depictionUrl(cid)));
      text.append("\n![Molecular structure](<").append(pubchem.depictionUrl(cid)).append(">)\n");
      if (gif) {
        try {
          var png = pubchem.depiction(cid);
          if (png != null) {
            if (!"image/png".equals(png.mediaType())) throw new IllegalStateException("Depiction is not image/png");
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(png.body()))) {
              var readers = ImageIO.getImageReaders(input);
              if (!readers.hasNext()) throw new IOException("Invalid PNG depiction");
              var reader = readers.next();
              try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048)
                  throw new IOException("Unsupported depiction dimensions or format");
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(reader.read(0), "gif", output)) throw new IOException("GIF writer unavailable");
                urls.put("image/gif", materialize(directory, output.toByteArray(), ".gif"));
              } finally { reader.dispose(); }
            }
          }
        } catch (IOException | RuntimeException e) {
          notifications.add(Notification.warning("PUBCHEM GIF depiction unavailable: " + e.getMessage()));
        }
      }
    }
    String markdown = text.toString();
    urls.put("text/markdown", materialize(directory, markdown.getBytes(StandardCharsets.UTF_8), ".md"));
    return new Result(Map.copyOf(urls), markdown, List.copyOf(notifications));
  }

  static Path defaultDirectory() {
    return Path.of(System.getProperty("java.io.tmpdir"), "klab.authority.pubchem", "documentation");
  }
  private static URL url(String value) {
    try { return URI.create(value).toURL(); }
    catch (IOException e) { throw new IllegalStateException("Invalid chemical documentation URL", e); }
  }
  private static synchronized URL materialize(Path directory, byte[] bytes, String extension) {
    try {
      Files.createDirectories(directory);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Path destination = directory.resolve(hash + extension);
      if (!Files.exists(destination)) {
        Path staging = Files.createTempFile(directory, "chemical-", ".tmp");
        try {
          Files.write(staging, bytes);
          try { Files.move(staging, destination); }
          catch (FileAlreadyExistsException ignored) { /* Identical content from another process. */ }
        } finally { Files.deleteIfExists(staging); }
      }
      return destination.toUri().toURL();
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot materialize chemical documentation", e);
    }
  }
  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
        .replace("[", "\\[").replace("]", "\\]").replace("`", "\\`")
        .replace("\r", " ").replace("\n", " ");
  }
}
