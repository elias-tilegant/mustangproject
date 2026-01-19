package org.mustangproject.api;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import org.mustangproject.ZUGFeRD.Version;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

public class ApiServer {
	private static final String DEFAULT_PORT = "8080";

	public static void main(String[] args) {
		int port = parsePort(envOrDefault("MUSTANG_API_PORT", envOrDefault("PORT", DEFAULT_PORT)));
		MustangService service = new MustangService();

		Javalin app = Javalin.create(config -> {
			config.http.defaultContentType = "application/json";
			config.staticFiles.enableWebjars();
		});

		app.exception(IllegalArgumentException.class, (e, ctx) ->
			ctx.status(400).json(Map.of("error", errorMessage(e))));
		app.exception(IllegalStateException.class, (e, ctx) ->
			ctx.status(422).json(Map.of("error", errorMessage(e))));
		app.exception(Exception.class, (e, ctx) ->
			ctx.status(500).json(Map.of("error", errorMessage(e))));

		app.get("/health", ctx ->
			ctx.json(Map.of("status", "ok", "version", Version.VERSION)));

		app.get("/openapi.yaml", ctx -> {
			byte[] bytes = readResourceBytes("/openapi.yaml");
			ctx.contentType("application/yaml").result(bytes);
		});

		app.get("/swagger", ctx -> {
			ctx.contentType("text/html");
			ctx.result(swaggerUiHtml());
		});

		app.get("/swagger/", ctx -> ctx.redirect("/swagger"));

		app.get("/api/xrechnung-viewer.css", ctx -> {
			byte[] bytes = readResourceBytes("/xrechnung-viewer.css");
			ctx.contentType("text/css").result(bytes);
		});

		app.get("/api/xrechnung-viewer.js", ctx -> {
			byte[] bytes = readResourceBytes("/xrechnung-viewer.js");
			ctx.contentType("application/javascript").result(bytes);
		});

		app.post("/api/validate", ctx -> {
			UploadedFile sourceFile = requireUpload(ctx.uploadedFile("source"), "source");
			byte[] sourceBytes = readUploadedBytes(sourceFile);
			String filename = safeFilename(sourceFile.filename());
			boolean noNotices = parseBoolean(ctx.formParam("noNotices"), false);
			String logAppend = ctx.formParam("logAppend");
			MustangService.ValidationResult result = service.validate(sourceBytes, filename, noNotices, logAppend);
			if (!result.optionsOk) {
				throw new IllegalArgumentException("Validation options not recognized");
			}
			ctx.status(result.valid ? 200 : 422);
			ctx.contentType("application/xml");
			ctx.result(result.validationXml);
		});

		app.post("/api/extract", ctx -> {
			UploadedFile pdfFile = requireUpload(ctx.uploadedFile("pdf"), "pdf");
			try (TempDir tempDir = TempDir.create()) {
				Path pdfPath = tempDir.writeUploadedFile(pdfFile, "input.pdf");
				Path outPath = tempDir.resolve("output.xml");
				service.extract(pdfPath, outPath);
				ctx.contentType("application/xml");
				ctx.header("Content-Disposition", "attachment; filename=\"extracted.xml\"");
				ctx.result(Files.readAllBytes(outPath));
			}
		});

		app.post("/api/a3only", ctx -> {
			UploadedFile pdfFile = requireUpload(ctx.uploadedFile("pdf"), "pdf");
			try (TempDir tempDir = TempDir.create()) {
				Path pdfPath = tempDir.writeUploadedFile(pdfFile, "input.pdf");
				Path outPath = tempDir.resolve("output.pdf");
				service.convertA3Only(pdfPath, outPath);
				ctx.contentType("application/pdf");
				ctx.header("Content-Disposition", "attachment; filename=\"converted.pdf\"");
				ctx.result(Files.readAllBytes(outPath));
			}
		});

		app.post("/api/combine", ctx -> {
			UploadedFile pdfFile = requireUpload(ctx.uploadedFile("pdf"), "pdf");
			UploadedFile xmlFile = requireUpload(ctx.uploadedFile("xml"), "xml");
			String format = defaultIfBlank(ctx.formParam("format"), "fx");
			int version = parseInt(ctx.formParam("version"), 1);
			String profile = ctx.formParam("profile");
			boolean ignoreInputErrors = parseBoolean(ctx.formParam("ignoreInputErrors"), false);
			List<MustangService.Attachment> attachments = readAttachments(ctx.uploadedFiles("attachments"));

			MustangService.CombineOptions options = new MustangService.CombineOptions(
				format, version, profile, ignoreInputErrors, attachments);

			try (TempDir tempDir = TempDir.create()) {
				Path pdfPath = tempDir.writeUploadedFile(pdfFile, "input.pdf");
				Path xmlPath = tempDir.writeUploadedFile(xmlFile, "input.xml");
				Path outPath = tempDir.resolve("output.pdf");
				service.combine(pdfPath, xmlPath, outPath, options);
				ctx.contentType("application/pdf");
				ctx.header("Content-Disposition", "attachment; filename=\"combined.pdf\"");
				ctx.result(Files.readAllBytes(outPath));
			}
		});

		app.post("/api/visualize", ctx -> {
			UploadedFile xmlFile = requireUpload(ctx.uploadedFile("xml"), "xml");
			String format = defaultIfBlank(ctx.formParam("format"), "html").toLowerCase(Locale.ROOT);
			String language = defaultIfBlank(ctx.formParam("language"), "en");
			if (!"html".equals(format) && !"pdf".equals(format)) {
				throw new IllegalArgumentException("format must be html or pdf");
			}
			if (!"en".equalsIgnoreCase(language) && !"de".equalsIgnoreCase(language) && !"fr".equalsIgnoreCase(language)) {
				throw new IllegalArgumentException("language must be en, de, or fr");
			}

			try (TempDir tempDir = TempDir.create()) {
				Path xmlPath = tempDir.writeUploadedFile(xmlFile, "input.xml");
				if ("pdf".equals(format)) {
					Path outPath = tempDir.resolve("output.pdf");
					service.visualizePdf(xmlPath, outPath);
					ctx.contentType("application/pdf");
					ctx.header("Content-Disposition", "attachment; filename=\"visualization.pdf\"");
					ctx.result(Files.readAllBytes(outPath));
				} else {
					Path outPath = tempDir.resolve("output.html");
					service.visualizeHtml(xmlPath, outPath, language);
					ctx.contentType("text/html");
					ctx.header("Content-Disposition", "attachment; filename=\"visualization.html\"");
					ctx.result(Files.readAllBytes(outPath));
				}
			}
		});

		app.post("/api/upgrade", ctx -> {
			UploadedFile xmlFile = requireUpload(ctx.uploadedFile("xml"), "xml");
			try (TempDir tempDir = TempDir.create()) {
				Path xmlPath = tempDir.writeUploadedFile(xmlFile, "input.xml");
				Path outPath = tempDir.resolve("output.xml");
				service.upgrade(xmlPath, outPath);
				ctx.contentType("application/xml");
				ctx.header("Content-Disposition", "attachment; filename=\"upgraded.xml\"");
				ctx.result(Files.readAllBytes(outPath));
			}
		});

		app.post("/api/ubl", ctx -> {
			UploadedFile xmlFile = requireUpload(ctx.uploadedFile("xml"), "xml");
			try (TempDir tempDir = TempDir.create()) {
				Path xmlPath = tempDir.writeUploadedFile(xmlFile, "input.xml");
				Path outPath = tempDir.resolve("output.xml");
				service.convertToUbl(xmlPath, outPath);
				ctx.contentType("application/xml");
				ctx.header("Content-Disposition", "attachment; filename=\"ubl.xml\"");
				ctx.result(Files.readAllBytes(outPath));
			}
		});

		app.start(port);
	}

	private static UploadedFile requireUpload(UploadedFile file, String name) {
		if (file == null) {
			throw new IllegalArgumentException("Missing file part: " + name);
		}
		return file;
	}

	private static byte[] readUploadedBytes(UploadedFile file) throws IOException {
		try (InputStream inputStream = file.content();
			 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, read);
			}
			return outputStream.toByteArray();
		}
	}

	private static List<MustangService.Attachment> readAttachments(List<UploadedFile> files) throws IOException {
		List<MustangService.Attachment> attachments = new ArrayList<>();
		if (files == null) {
			return attachments;
		}
		for (UploadedFile file : files) {
			String filename = safeFilename(file.filename());
			if (filename.isEmpty()) {
				filename = "attachment";
			}
			String contentType = file.contentType();
			byte[] data = readUploadedBytes(file);
			attachments.add(new MustangService.Attachment(filename, contentType, data));
		}
		return attachments;
	}

	private static String defaultIfBlank(String value, String defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		return value;
	}

	private static boolean parseBoolean(String value, boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "1":
			case "true":
			case "yes":
			case "y":
				return true;
			case "0":
			case "false":
			case "no":
			case "n":
				return false;
			default:
				return defaultValue;
		}
	}

	private static int parseInt(String value, int defaultValue) {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid integer: " + value);
		}
	}

	private static int parsePort(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return Integer.parseInt(DEFAULT_PORT);
		}
	}

	private static String envOrDefault(String name, String defaultValue) {
		String value = System.getenv(name);
		return value == null || value.isEmpty() ? defaultValue : value;
	}

	private static String errorMessage(Exception e) {
		String message = e.getMessage();
		return message == null || message.isEmpty() ? "Unexpected error" : message;
	}

	private static String swaggerUiHtml() {
		String version = swaggerUiVersion();
		String basePath = version.isEmpty() ? "/webjars/swagger-ui" : "/webjars/swagger-ui/" + version;

		StringBuilder html = new StringBuilder();
		html.append("<!doctype html>\n");
		html.append("<html lang=\"en\">\n");
		html.append("<head>\n");
		html.append("  <meta charset=\"utf-8\" />\n");
		html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />\n");
		html.append("  <title>Mustang API - Swagger UI</title>\n");
		html.append("  <link rel=\"stylesheet\" href=\"").append(basePath).append("/swagger-ui.css\" />\n");
		html.append("</head>\n");
		html.append("<body>\n");
		html.append("<div id=\"swagger-ui\"></div>\n");
		html.append("<script src=\"").append(basePath).append("/swagger-ui-bundle.js\"></script>\n");
		html.append("<script src=\"").append(basePath).append("/swagger-ui-standalone-preset.js\"></script>\n");
		html.append("<script>\n");
		html.append("window.onload = function() {\n");
		html.append("  window.ui = SwaggerUIBundle({\n");
		html.append("    url: '/openapi.yaml',\n");
		html.append("    dom_id: '#swagger-ui',\n");
		html.append("    presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],\n");
		html.append("    layout: 'BaseLayout'\n");
		html.append("  });\n");
		html.append("};\n");
		html.append("</script>\n");
		html.append("</body>\n");
		html.append("</html>\n");
		return html.toString();
	}

	private static String swaggerUiVersion() {
		try (InputStream inputStream = ApiServer.class.getResourceAsStream(
			"/META-INF/maven/org.webjars/swagger-ui/pom.properties")) {
			if (inputStream == null) {
				return "";
			}
			Properties properties = new Properties();
			properties.load(inputStream);
			String version = properties.getProperty("version");
			return version == null ? "" : version.trim();
		} catch (IOException e) {
			return "";
		}
	}

	private static byte[] readResourceBytes(String resourcePath) throws IOException {
		try (InputStream inputStream = ApiServer.class.getResourceAsStream(resourcePath)) {
			if (inputStream == null) {
				throw new IOException("Resource not found: " + resourcePath);
			}
			return inputStream.readAllBytes();
		}
	}

	private static String safeFilename(String filename) {
		if (filename == null) {
			return "";
		}
		try {
			Path path = Path.of(filename).getFileName();
			return path == null ? "" : path.toString();
		} catch (Exception e) {
			return "";
		}
	}

	private static final class TempDir implements AutoCloseable {
		private final Path dir;

		private TempDir(Path dir) {
			this.dir = dir;
		}

		static TempDir create() throws IOException {
			return new TempDir(Files.createTempDirectory("mustang-api-"));
		}

		Path resolve(String fileName) {
			return dir.resolve(fileName);
		}

		Path writeUploadedFile(UploadedFile file, String fallbackName) throws IOException {
			String safeName = safeFilename(file.filename());
			if (safeName.isEmpty()) {
				safeName = fallbackName;
			}
			Path destination = dir.resolve(safeName);
			try (InputStream inputStream = file.content()) {
				Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
			}
			return destination;
		}

		@Override
		public void close() throws IOException {
			deleteRecursively(dir);
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		}
	}
}
