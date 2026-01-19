package org.mustangproject.api;

import org.mustangproject.CII.CIIToUBL;
import org.mustangproject.EStandard;
import org.mustangproject.ZUGFeRD.*;
import org.mustangproject.validator.ZUGFeRDValidator;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MustangService {
	public static final class Attachment {
		public final String filename;
		public final String mimeType;
		public final byte[] data;

		public Attachment(String filename, String mimeType, byte[] data) {
			this.filename = filename;
			this.mimeType = mimeType;
			this.data = data;
		}
	}

	public static final class CombineOptions {
		public final String format;
		public final int version;
		public final String profile;
		public final boolean ignoreInputErrors;
		public final List<Attachment> attachments;

		public CombineOptions(String format, int version, String profile, boolean ignoreInputErrors,
							 List<Attachment> attachments) {
			this.format = format;
			this.version = version;
			this.profile = profile;
			this.ignoreInputErrors = ignoreInputErrors;
			this.attachments = attachments == null ? Collections.emptyList() : attachments;
		}
	}

	public static final class ValidationResult {
		public final String validationXml;
		public final boolean valid;
		public final boolean optionsOk;

		public ValidationResult(String validationXml, boolean valid, boolean optionsOk) {
			this.validationXml = validationXml;
			this.valid = valid;
			this.optionsOk = optionsOk;
		}
	}

	public ValidationResult validate(byte[] bytes, String filename, boolean noNotices, String logAppend) {
		ZUGFeRDValidator zfv = new ZUGFeRDValidator();
		if (logAppend != null && !logAppend.isEmpty()) {
			zfv.setLogAppend(logAppend);
		}
		if (noNotices) {
			zfv.disableNotices();
		}
		String name = filename == null || filename.isEmpty() ? "source" : filename;
		String validationResultXml = zfv.validate(bytes, name);
		return new ValidationResult(validationResultXml, zfv.wasCompletelyValid(), !zfv.hasOptionsError());
	}

	public void extract(Path pdfPath, Path xmlOutputPath) throws IOException {
		ZUGFeRDImporter zi = new ZUGFeRDImporter();
		zi.doIgnoreCalculationErrors();
		zi.setPDFFilename(pdfPath.toString());
		byte[] xmlContent = zi.getRawXML();
		if (xmlContent == null) {
			throw new IllegalStateException("No ZUGFeRD XML found in PDF file");
		}
		Files.write(xmlOutputPath, xmlContent);
	}

	public void convertA3Only(Path pdfPath, Path outPath) throws IOException {
		try (ZUGFeRDExporterFromA1 ze = new ZUGFeRDExporterFromA1()) {
			ze.convertOnly().load(pdfPath.toString());
			ze.export(outPath.toString());
		}
	}

	public void combine(Path pdfPath, Path xmlPath, Path outPath, CombineOptions options) throws Exception {
		String format = normalizeFormat(options.format);
		int version = options.version;

		if (!"fx".equals(format) && !"zf".equals(format) && !"ox".equals(format) && !"da".equals(format)) {
			throw new IllegalArgumentException("Unknown format '" + options.format + "'");
		}
		if (version != 1 && version != 2) {
			throw new IllegalArgumentException("version must be 1 or 2");
		}
		if ("fx".equals(format) && version > 1) {
			throw new IllegalArgumentException("Factur-X is only available in version 1");
		}

		EStandard standard = resolveStandard(format);
		String profileCode = resolveProfileCode(format, version, options.profile);
		Profile profile = resolveProfile(format, standard, version, profileCode);

		IZUGFeRDExporter exporter = buildExporter(format, options.ignoreInputErrors);
		int exporterVersion = "fx".equals(format) ? 2 : version;

		exporter.load(pdfPath.toString());
		exporter.setProducer("Mustang-API")
			.setZUGFeRDVersion(exporterVersion)
			.setCreator("mustang-api")
			.setProfile(profile);

		if ("zf".equals(format)) {
			exporter.disableFacturX();
		}

		for (Attachment attachment : options.attachments) {
			String mime = attachment.mimeType == null || attachment.mimeType.isEmpty()
				? "application/octet-stream"
				: attachment.mimeType;
			exporter.attachFile(attachment.filename, attachment.data, mime, "Data");
		}

		exporter.setXML(Files.readAllBytes(xmlPath));
		exporter.export(outPath.toString());
	}

	public void visualizeHtml(Path xmlPath, Path outPath, String language) throws Exception {
		ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
		ZUGFeRDVisualizer.Language langCode = toLanguage(language);
		String html = zvi.visualize(xmlPath.toString(), langCode);
		Files.write(outPath, html.getBytes(StandardCharsets.UTF_8));
	}

	public void visualizePdf(Path xmlPath, Path outPath) throws Exception {
		ZUGFeRDVisualizer zvi = new ZUGFeRDVisualizer();
		zvi.toPDF(xmlPath.toString(), outPath.toString());
	}

	public void upgrade(Path xmlPath, Path outPath) throws IOException, TransformerException {
		XMLUpgrader zmi = new XMLUpgrader();
		String xml = zmi.migrateFromV1ToV2(xmlPath.toString());
		Files.write(outPath, xml.getBytes(StandardCharsets.UTF_8));
	}

	public void convertToUbl(Path xmlPath, Path outPath) throws IOException, TransformerException {
		CIIToUBL c2u = new CIIToUBL();
		c2u.convert(xmlPath.toFile(), outPath.toFile());
	}

	private IZUGFeRDExporter buildExporter(String format, boolean ignoreInputErrors) {
		if ("ox".equals(format)) {
			OXExporterFromA1 exporter = new OXExporterFromA1();
			if (ignoreInputErrors) {
				exporter.ignorePDFAErrors();
			}
			return exporter;
		}
		if ("da".equals(format)) {
			DXExporterFromA1 exporter = new DXExporterFromA1();
			if (ignoreInputErrors) {
				exporter.ignorePDFAErrors();
			}
			return exporter;
		}
		ZUGFeRDExporterFromPDFA exporter = new ZUGFeRDExporterFromPDFA();
		if (ignoreInputErrors) {
			exporter.ignorePDFAErrors();
		}
		return exporter;
	}

	private String normalizeFormat(String format) {
		if (format == null || format.isEmpty()) {
			return "fx";
		}
		return format.trim().toLowerCase(Locale.ROOT);
	}

	private EStandard resolveStandard(String format) {
		if ("zf".equals(format)) {
			return EStandard.zugferd;
		}
		if ("ox".equals(format)) {
			return EStandard.orderx;
		}
		if ("da".equals(format)) {
			return EStandard.despatchadvice;
		}
		return EStandard.facturx;
	}

	private String resolveProfileCode(String format, int version, String profile) {
		if (profile != null && !profile.trim().isEmpty()) {
			return profile.trim().toLowerCase(Locale.ROOT);
		}
		if ("da".equals(format)) {
			return "p";
		}
		if ("ox".equals(format) || ("zf".equals(format) && version == 1)) {
			return "t";
		}
		return "e";
	}

	private Profile resolveProfile(String format, EStandard standard, int version, String profileCode) {
		String code = profileCode.toLowerCase(Locale.ROOT);
		if ("da".equals(format)) {
			if (!"p".equals(code)) {
				throw new IllegalArgumentException("Unknown profile '" + profileCode + "'");
			}
			return safeProfileLookup(standard, "PILOT", 1, profileCode);
		}
		if ("ox".equals(format) || ("zf".equals(format) && version == 1)) {
			if ("b".equals(code)) {
				return safeProfileLookup(standard, "BASIC", version, profileCode);
			}
			if ("c".equals(code)) {
				return safeProfileLookup(standard, "COMFORT", version, profileCode);
			}
			if ("t".equals(code)) {
				return safeProfileLookup(standard, "EXTENDED", version, profileCode);
			}
			throw new IllegalArgumentException("Unknown profile '" + profileCode + "'");
		}

		if ("m".equals(code)) {
			return safeProfileLookup(standard, "MINIMUM", version, profileCode);
		}
		if ("w".equals(code)) {
			return safeProfileLookup(standard, "BASICWL", version, profileCode);
		}
		if ("b".equals(code)) {
			return safeProfileLookup(standard, "BASIC", version, profileCode);
		}
		if ("c".equals(code)) {
			return safeProfileLookup(standard, "CIUS", version, profileCode);
		}
		if ("e".equals(code)) {
			return safeProfileLookup(standard, "EN16931", version, profileCode);
		}
		if ("t".equals(code)) {
			return safeProfileLookup(standard, "EXTENDED", version, profileCode);
		}
		if ("x".equals(code)) {
			return safeProfileLookup(standard, "XRECHNUNG", version, profileCode);
		}
		throw new IllegalArgumentException("Unknown profile '" + profileCode + "'");
	}

	private Profile safeProfileLookup(EStandard standard, String name, int version, String originalProfile) {
		try {
			return Profiles.getByName(standard, name, version);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Unknown profile '" + originalProfile + "'");
		}
	}

	private ZUGFeRDVisualizer.Language toLanguage(String language) {
		if (language == null) {
			return ZUGFeRDVisualizer.Language.EN;
		}
		switch (language.trim().toLowerCase(Locale.ROOT)) {
			case "de":
				return ZUGFeRDVisualizer.Language.DE;
			case "fr":
				return ZUGFeRDVisualizer.Language.FR;
			case "en":
			default:
				return ZUGFeRDVisualizer.Language.EN;
		}
	}
}
