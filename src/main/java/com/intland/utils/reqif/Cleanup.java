package com.intland.utils.reqif;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.xerces.dom.DOMInputImpl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

public class Cleanup {



	private static final DocumentBuilder PARSER;
	private static final Transformer TRANSFORMER;
	private static final Validator VALIDATOR;

	private static boolean debug;

	static {
		try {
			SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			schemaFactory.setResourceResolver(new LSResourceResolver() {

				@Override
				public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
					String name;
					if(systemId.contains("/")) {
						int pathEnd = systemId.lastIndexOf('/');
						name = systemId.substring(pathEnd);
					} else {
						name = "/" + systemId;
					}

					InputStream data = Cleanup.class.getResourceAsStream(name);
					return data == null ? null : new DOMInputImpl(publicId, systemId, systemId, data, "UTF-8");
				}

			});

			Schema schema = schemaFactory.newSchema(new StreamSource(Cleanup.class.getResourceAsStream("/reqif.xsd")));
			VALIDATOR = schema.newValidator();
			VALIDATOR.setErrorHandler(new ErrorHandler() {

				@Override
				public void warning(SAXParseException exception) throws SAXException {
					System.out.println(String.format("VALIDATION %s\t%s:%s\t%s", "WARNING", exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
				}

				@Override
				public void error(SAXParseException exception) throws SAXException {
					System.out.println(String.format("VALIDATION %s\t%s:%s\t%s", "ERROR", exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
				}

				@Override
				public void fatalError(SAXParseException exception) throws SAXException {
					System.out.println(String.format("VALIDATION %s\t%s:%s\t%s", "FATAL", exception.getLineNumber(), exception.getColumnNumber(), exception.getMessage()));
				}



			});

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			PARSER = factory.newDocumentBuilder();
			TRANSFORMER = TransformerFactory.newInstance().newTransformer();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) throws IOException {

		List<String> asList = Arrays.asList(args);
		debug = asList.contains("debug");
		boolean cleanup = asList.contains("cleanup");
		boolean identifier = asList.contains("identifier");
		boolean validate = asList.contains("validate");

		boolean move = identifier || cleanup;

		List<Path> reqifzFiles = Files.list(Paths.get("input"))
				.filter(Files::isRegularFile)
				.filter(p -> p.getFileName().toString().endsWith(".reqifz"))
				.map(p -> move ? Cleanup.moveToOutput(p) : p)
				.collect(Collectors.toList());

		for (Path reqifzFile : reqifzFiles) {
			Path zipPath = addSuffix(reqifzFile);

			try (FileSystem zip = loadZip(zipPath)) {
				loadReqIfFiles(zip).map(ReqIfFile::new).forEach(reqif -> {
					boolean identifierAdded = identifier && reqif.addIdentifier();
					boolean configChanged = cleanup && reqif.removeCbConfiguration();
					if (configChanged || identifierAdded) {
						try {
							reqif.save();
						} catch (IOException | TransformerException e) {
							throw new RuntimeException(e);
						}
					}
					if(validate) {
						reqif.validate();
					}
				});
			}

			removeSuffix(zipPath);
		}

	}

	private static Path addSuffix(Path reqifzFile) throws IOException {
		Path zip = reqifzFile.getParent().resolve(reqifzFile.getFileName().toString() + ".zip");
		return Files.move(reqifzFile, zip, StandardCopyOption.REPLACE_EXISTING);
	}

	private static Path removeSuffix(Path reqifzFile) throws IOException {
		String name = reqifzFile.getFileName().toString();
		return Files.move(reqifzFile, reqifzFile.getParent().resolve(name.substring(0, name.length() - 4)));
	}

	private static class ReqIfFile {
		private static final String REQIF_NS = "http://www.omg.org/spec/ReqIF/20110401/reqif.xsd";
		private final Path path;
		private final Document document;

		public ReqIfFile(Path file) {
			this.path = file;
			this.document = loadDOM(file);
		}


		private Document loadDOM(Path path) {
			System.out.println("Parsing " + path);
			try {
				try (InputStream is = Files.newInputStream(path)) {
					return PARSER.parse(is);
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public boolean removeCbConfiguration() {
			return removeCbNodes(findCbNodes());
		}

		private boolean removeCbNodes(Collection<Node> toRemove) {
			HashSet<Node> removed = new HashSet<>();
			for (Node remove : toRemove) {
				Node parent = remove.getParentNode();
				if (!removed.contains(parent)  && !removed.contains(parent.getParentNode())) {
					boolean isConfiguraiotn = isConfiguration(remove);
					boolean isData = isData(remove);

					if (isData) {
						String identifier = Optional.ofNullable(remove.getParentNode())
							.map(Node::getParentNode)
							.map(Node::getAttributes)
							.map(it -> it.getNamedItem("IDENTIFIER"))
							.map(Node::getNodeValue)
							.orElse("UNKNOWN");

						System.out.println("WARNING: Removing data from " + identifier);
						System.out.println(toString(remove));
					}
					if (isData || isConfiguraiotn) {
						parent.removeChild(remove);
						removed.add(remove);
					}
				} else {
					removed.add(remove);
				}

				if (!removed.contains(remove)) {
					if (!remove.getNodeName().equals("REQ-IF-HEADER")) {
						System.out.println("WARNING: Unexpected element with CB ID not removed!");
						System.out.println(toString(remove));
					}
				}
			}

			System.out.println("Remvoved " + removed.size() + " elements.");
			return !removed.isEmpty();
		}

		public boolean addIdentifier() {
			Element header = (Element)document.getElementsByTagNameNS(REQIF_NS, "REQ-IF-HEADER").item(0);
			NodeList repositoryIdList = document.getElementsByTagNameNS(REQIF_NS, "REPOSITORY-ID");
			Element repositoryId;
			if(repositoryIdList.getLength() == 0) {
				repositoryId = document.createElementNS(REQIF_NS, "REPOSITORY-ID");
				header.appendChild(repositoryId);
			} else {
				repositoryId = (Element)repositoryIdList.item(0);
			}

			String allSpecIds = "";
			NodeList allSpecificaitons = document.getElementsByTagNameNS(REQIF_NS, "SPECIFICATION");
			for(int i = 0; i < allSpecificaitons.getLength(); i++) {
				allSpecIds += ((Element)allSpecificaitons.item(i)).getAttribute("IDENTIFIER");
			}


			if(repositoryId.getTextContent() == null || repositoryId.getTextContent().isEmpty()) {
				repositoryId.setTextContent(allSpecIds);
				return true;
			}

			return false;
		}

		private Collection<Node> findCbNodes() {
			NodeList allElements = document.getElementsByTagName("*");
			Collection<Node> toRemove = new ArrayList<>();
			System.out.println(allElements.getLength());
			for (int i = 0; i < allElements.getLength(); i++) {
				Element element = (Element) allElements.item(i);
				String identifier = element.getAttribute("IDENTIFIER");
				if (identifier.startsWith("CB-")) {
					toRemove.add(element);
				}

				String name = element.getNodeName();
				if (name.startsWith("ATTRIBUTE-DEFINITION-") && name.endsWith("-REF")) {
					String ref = element.getTextContent();
					if (ref.startsWith("CB-")) {
						toRemove.add(element.getParentNode().getParentNode());
					}
				}
			}

			System.out.println("Found " + toRemove.size() + " nodes with codeBeamer ID");
			return toRemove;
		}

		private String toString(Node remove) {
			StringWriter writer = new StringWriter();
			try {
				TRANSFORMER.transform(new DOMSource(remove), new StreamResult(writer));
			} catch (TransformerException e) {
				e.printStackTrace();
			}

			return writer.toString().replace("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "");
		}

		private boolean isConfiguration(Node element) {
			String name = element.getNodeName();
			return name.startsWith("DATATYPE-DEFINITION-") || name.equals("SPEC-OBJECT-TYPE")
					|| name.startsWith("ATTRIBUTE-DEFINITION-");

		}

		private boolean isData(Node node) {
			String name = node.getNodeName();
			return name.equals("SPEC-OBJECT")
					|| name.equals("SPEC-HIERARCHY")
					|| (name.startsWith("ATTRIBUTE-VALUE-") && !node.getParentNode().getNodeName().equals("DEFAULT-VALUE"));
		}

		public void validate()  {
			try {

				try (InputStream in = Files.newInputStream(path)) {
					VALIDATOR.validate(new StreamSource(in));
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}

		public void save() throws IOException, TransformerException {
			System.out.println("Saving " + path);
			try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.WRITE)) {
				TRANSFORMER.transform(new DOMSource(document), new StreamResult(out));
			}
		}

	}

	private static Stream<Path> loadReqIfFiles(FileSystem fs) {
		try {
			return Files.list(fs.getPath("/")).filter(p -> p.getFileName().toString().endsWith(".reqif"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static FileSystem loadZip(Path path) {
		System.out.println("## Load " + path);
		try {
			return FileSystems.newFileSystem(path, null);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static Path moveToOutput(Path p) {
		try {
			return debug ?
					Files.copy(p, Paths.get("output").resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING) :
					Files.move(p, Paths.get("output").resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
