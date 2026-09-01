package org.docx4j.mcp;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.docx4j.TraversalUtil;
import org.docx4j.XmlUtils;
import org.docx4j.jaxb.Context;
import org.docx4j.model.datastorage.CustomXmlDataStoragePartSelector;
import org.docx4j.model.fields.ComplexFieldLocator;
import org.docx4j.model.fields.FieldRef;
import org.docx4j.model.fields.FieldsPreprocessor;
import org.docx4j.model.fields.merge.MailMerger;
import org.docx4j.model.sdt.QueryString;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.CustomXmlPart;
import org.docx4j.openpackaging.parts.JaxbXmlPart;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.openpackaging.parts.WordprocessingML.MainDocumentPart;
import org.docx4j.openpackaging.parts.opendope.ConditionsPart;
import org.docx4j.openpackaging.parts.opendope.XPathsPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.CTDataBinding;
import org.docx4j.wml.ContentAccessor;
import org.docx4j.wml.P;
import org.docx4j.wml.SdtElement;
import org.docx4j.wml.SdtPr;
import org.docx4j.wml.Tbl;
import org.opendope.conditions.And;
import org.opendope.conditions.Condition;
import org.opendope.conditions.Conditionref;
import org.opendope.conditions.Evaluable;
import org.opendope.conditions.Not;
import org.opendope.conditions.Or;
import org.opendope.conditions.Xpathref;
import org.opendope.xpaths.Xpaths;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * What a template wants: OpenDoPE xpaths/conditions plus the skeleton XML, plain content
 * controls, or MERGEFIELD names.  Shared by describe_template and fill_template.
 */
final class TemplateInspector {

	enum Kind { OPENDOPE, CONTENT_CONTROLS, MAIL_MERGE, NONE }

	record ContentControl(String tag, String title, String role, String xpath, String store_item_id, String part) {}

	record XPathInfo(String id, String xpath, String store_item_id, String prefix_mappings, String type, String question_id) {}

	record ConditionInfo(String id, String expression) {}

	record Description(
			String kind,
			String data_format,
			String how_to_fill,
			String skeleton_xml,
			List<XPathInfo> xpaths,
			List<ConditionInfo> conditions,
			List<ContentControl> content_controls,
			List<String> merge_fields,
			Map<String, String> merge_field_formats,
			Map<String, Object> document) {}

	private TemplateInspector() {}

	static Description describe(WordprocessingMLPackage pkg) throws Exception {
		MainDocumentPart mdp = pkg.getMainDocumentPart();

		List<XPathInfo> xpaths = xpaths(mdp);
		List<ConditionInfo> conditions = conditions(mdp, xpaths);
		List<ContentControl> controls = contentControls(pkg);
		Map<String, String> mergeFieldMap = mergeFields(pkg);
		List<String> mergeFields = new ArrayList<>(mergeFieldMap.keySet());
		Map<String, String> formats = new LinkedHashMap<>();
		mergeFieldMap.forEach((name, switches) -> {
			if (switches != null && (switches.contains("\\@") || switches.contains("\\#"))) {
				formats.put(name, switches);
			}
		});
		String skeleton = skeletonXml(pkg);
		Kind kind = kind(mdp, controls, mergeFields, skeleton);

		String dataFormat;
		String how;
		switch (kind) {
			case OPENDOPE -> {
				dataFormat = "xml";
				how = "OpenDoPE template. Copy skeleton_xml, replace the sample values with real data (add or remove "
						+ "repeated elements for repeats; conditions are evaluated against the data), and pass the whole "
						+ "XML document as the 'data' string to fill_template.";
			}
			case CONTENT_CONTROLS -> {
				dataFormat = "xml";
				how = "Content controls bound to a custom XML part. Copy skeleton_xml, replace the values at the bound "
						+ "xpaths, and pass the XML document as the 'data' string to fill_template.";
			}
			case MAIL_MERGE -> {
				dataFormat = "json";
				how = "Mail-merge template. Pass 'data' to fill_template as a JSON object mapping each name in "
						+ "merge_fields to its value (names are matched case-insensitively)."
						+ (formats.isEmpty() ? "" : " Fields listed in merge_field_formats carry a date (\\@) or "
								+ "number (\\#) format switch: give dates as 2026-09-01, 20260901, 01/09/2026 (day first) or "
								+ "1 September 2026, and numbers as plain digits like 1234.5.");
			}
			default -> {
				dataFormat = null;
				how = controls.isEmpty()
						? "No fillable template structure found (no bound content controls, no MERGEFIELDs). "
								+ "fill_template cannot fill this document."
						: "Content controls without data bindings: fill_template cannot fill them automatically.";
			}
		}

		return new Description(kind.name().toLowerCase(), dataFormat, how,
				kind == Kind.OPENDOPE || kind == Kind.CONTENT_CONTROLS ? skeleton : null,
				xpaths, conditions, controls, mergeFields, formats, summary(pkg));
	}

	static Kind kind(MainDocumentPart mdp, List<ContentControl> controls, List<String> mergeFields, String skeleton) {
		if (mdp.getXPathsPart() != null) {
			return Kind.OPENDOPE;
		}
		if (skeleton != null && controls.stream().anyMatch(c -> c.xpath() != null)) {
			return Kind.CONTENT_CONTROLS;
		}
		if (!mergeFields.isEmpty()) {
			return Kind.MAIL_MERGE;
		}
		return Kind.NONE;
	}

	/** The custom XML data part's current content (usually the designer's sample data), pretty-printed. */
	static String skeletonXml(WordprocessingMLPackage pkg) {
		try {
			CustomXmlPart part = CustomXmlDataStoragePartSelector.getCustomXmlDataStoragePart(pkg);
			if (part == null) {
				return null;
			}
			return prettyPrint(part.getXML());
		} catch (Docx4JException e) {
			return null;
		}
	}

	static List<XPathInfo> xpaths(MainDocumentPart mdp) throws Docx4JException {
		XPathsPart part = mdp.getXPathsPart();
		List<XPathInfo> out = new ArrayList<>();
		if (part == null) {
			return out;
		}
		for (Xpaths.Xpath x : part.getContents().getXpath()) {
			Xpaths.Xpath.DataBinding db = x.getDataBinding();
			out.add(new XPathInfo(x.getId(),
					db == null ? null : db.getXpath(),
					db == null ? null : db.getStoreItemID(),
					db == null ? null : db.getPrefixMappings(),
					x.getType(), x.getQuestionID()));
		}
		return out;
	}

	static List<ConditionInfo> conditions(MainDocumentPart mdp, List<XPathInfo> xpaths) throws Docx4JException {
		ConditionsPart part = mdp.getConditionsPart();
		List<ConditionInfo> out = new ArrayList<>();
		if (part == null) {
			return out;
		}
		Map<String, String> xpathById = new LinkedHashMap<>();
		for (XPathInfo x : xpaths) {
			xpathById.put(x.id(), x.xpath());
		}
		for (Condition c : part.getContents().getCondition()) {
			out.add(new ConditionInfo(c.getId(), render(c.getParticle(), xpathById)));
		}
		return out;
	}

	private static String render(Evaluable e, Map<String, String> xpathById) {
		if (e instanceof Xpathref x) {
			String xp = xpathById.get(x.getId());
			return xp == null ? "xpath(" + x.getId() + ")" : xp;
		}
		if (e instanceof Conditionref c) {
			return "condition(" + c.getId() + ")";
		}
		if (e instanceof Not n) {
			return "not(" + render(n.getParticle(), xpathById) + ")";
		}
		if (e instanceof And a) {
			return "(" + String.join(" and ", a.getXpathrefOrAndOrOr().stream().map(p -> render(p, xpathById)).toList()) + ")";
		}
		if (e instanceof Or o) {
			return "(" + String.join(" or ", o.getXpathrefOrAndOrOr().stream().map(p -> render(p, xpathById)).toList()) + ")";
		}
		return e == null ? "?" : e.getClass().getSimpleName();
	}

	/** Every sdt in the body, headers and footers, with its tag, title, binding and OpenDoPE role. */
	static List<ContentControl> contentControls(WordprocessingMLPackage pkg) {
		List<ContentControl> out = new ArrayList<>();
		for (Map.Entry<String, JaxbXmlPart<?>> e : storyParts(pkg).entrySet()) {
			String partName = e.getKey();
			new TraversalUtil(e.getValue().getJaxbElement(), new TraversalUtil.CallbackImpl() {
				@Override
				public List<Object> apply(Object o) {
					o = XmlUtils.unwrap(o);
					if (o instanceof SdtElement sdt) {
						out.add(describeControl(sdt, partName));
					}
					return null;
				}
			});
		}
		return out;
	}

	private static ContentControl describeControl(SdtElement sdt, String partName) {
		SdtPr pr = sdt.getSdtPr();
		String tag = pr == null || pr.getTag() == null ? null : pr.getTag().getVal();
		String title = null;
		CTDataBinding db = pr == null ? null : pr.getDataBinding();
		if (pr != null) {
			for (Object o : pr.getRPrOrAliasOrLock()) {
				o = XmlUtils.unwrap(o);
				if (o instanceof SdtPr.Alias a) {
					title = a.getVal();
				}
			}
		}
		String role = "plain";
		if (tag != null) {
			Map<String, String> q = QueryString.parseQueryString(tag, true);
			if (q.containsKey("od:repeat")) {
				role = "repeat";
			} else if (q.containsKey("od:condition")) {
				role = "condition";
			} else if (q.containsKey("od:xpath") || db != null) {
				role = "bind";
			}
		} else if (db != null) {
			role = "bind";
		}
		return new ContentControl(tag, title, role,
				db == null ? null : db.getXpath(),
				db == null ? null : db.getStoreItemID(),
				partName);
	}

	/** MERGEFIELD names (first spelling seen; matched case-insensitively) in the body, headers and footers. */
	static List<String> mergeFieldNames(WordprocessingMLPackage pkg) throws Docx4JException {
		return new ArrayList<>(mergeFields(pkg).keySet());
	}

	/**
	 * MERGEFIELD name to its remaining field switches (eg {@code \@ "d MMMM yyyy" \* MERGEFORMAT}),
	 * or null when there are none.  Converts simple fields to complex form as a side effect
	 * (MailMerger does the same), so call this on a package you own.
	 */
	static Map<String, String> mergeFields(WordprocessingMLPackage pkg) throws Docx4JException {
		Map<String, String> byUpper = new LinkedHashMap<>();
		Map<String, String> result = new LinkedHashMap<>();
		for (JaxbXmlPart<?> part : storyParts(pkg).values()) {
			FieldsPreprocessor.complexifyFields(part);
			Body shell = Context.getWmlObjectFactory().createBody();
			if (part instanceof MainDocumentPart mdp) {
				shell.getContent().addAll(mdp.getContent());
			} else if (part.getJaxbElement() instanceof ContentAccessor ca) {
				shell.getContent().addAll(ca.getContent());
			}
			ComplexFieldLocator locator = new ComplexFieldLocator();
			new TraversalUtil(shell, locator);
			for (P p : locator.getStarts()) {
				List<FieldRef> refs = new ArrayList<>();
				FieldsPreprocessor.canonicalise(p, refs);
				for (FieldRef fr : refs) {
					if (!"MERGEFIELD".equals(fr.getFldName())) {
						continue;
					}
					String instr = FieldsPreprocessor.extractInstr(fr.getInstructions());
					if (instr == null) {
						continue;
					}
					String name = MergeFieldNames.of(instr);
					if (name == null || name.isBlank()) {
						continue;
					}
					String key = name.toUpperCase();
					String switches = switchesAfterName(instr, name);
					if (!byUpper.containsKey(key)) {
						byUpper.put(key, name);
						result.put(name, switches);
					} else if (switches != null && result.get(byUpper.get(key)) == null) {
						result.put(byUpper.get(key), switches);
					}
				}
			}
		}
		return result;
	}

	private static String switchesAfterName(String instr, String name) {
		int i = instr.indexOf(name, instr.indexOf("MERGEFIELD") + 10);
		if (i < 0) {
			return null;
		}
		String rest = instr.substring(i + name.length()).trim();
		if (rest.startsWith("\"")) {
			rest = rest.substring(1).trim();
		}
		return rest.isEmpty() ? null : rest;
	}

	/** Reaches MailMerger's protected field-name parser so we agree with it exactly. */
	private static final class MergeFieldNames extends MailMerger {
		private MergeFieldNames() {
			super(null);
		}

		static String of(String instr) {
			return getDatafieldNameFromInstr(instr);
		}
	}

	/** Main document part plus every header and footer part, keyed by part name. */
	static Map<String, JaxbXmlPart<?>> storyParts(WordprocessingMLPackage pkg) {
		Map<String, JaxbXmlPart<?>> parts = new LinkedHashMap<>();
		MainDocumentPart mdp = pkg.getMainDocumentPart();
		parts.put(mdp.getPartName().getName(), mdp);
		for (Part p : pkg.getParts().getParts().values()) {
			if (p instanceof HeaderPart || p instanceof FooterPart) {
				parts.put(p.getPartName().getName(), (JaxbXmlPart<?>) p);
			}
		}
		return parts;
	}

	static Map<String, Object> summary(WordprocessingMLPackage pkg) {
		MainDocumentPart mdp = pkg.getMainDocumentPart();
		int[] counts = new int[2];
		new TraversalUtil(mdp.getContent(), new TraversalUtil.CallbackImpl() {
			@Override
			public List<Object> apply(Object o) {
				o = XmlUtils.unwrap(o);
				if (o instanceof P) {
					counts[0]++;
				} else if (o instanceof Tbl) {
					counts[1]++;
				}
				return null;
			}
		});
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("paragraphs", counts[0]);
		m.put("tables", counts[1]);
		m.put("styles_in_use", new ArrayList<>(mdp.getStylesInUse()));
		m.put("fonts_in_use", new ArrayList<>(mdp.fontsInUse()));
		m.put("parts", pkg.getParts().getParts().size());
		return m;
	}

	static String prettyPrint(String xml) {
		try {
			Document doc = XmlUtils.getNewDocumentBuilder().parse(new InputSource(new StringReader(xml)));
			Transformer t = TransformerFactory.newInstance().newTransformer();
			t.setOutputProperty(OutputKeys.INDENT, "yes");
			t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			StringWriter sw = new StringWriter();
			t.transform(new DOMSource(doc), new StreamResult(sw));
			return sw.toString().trim();
		} catch (Exception e) {
			return xml;
		}
	}
}
