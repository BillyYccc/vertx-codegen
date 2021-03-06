package io.vertx.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.codegen.annotations.DataObject;
import io.vertx.codegen.annotations.ModuleGen;
import io.vertx.codegen.annotations.ProxyGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.codegen.type.ClassKind;
import io.vertx.codegen.type.TypeNameTranslator;
import org.mvel2.MVEL;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@javax.annotation.processing.SupportedOptions({"codegen.output","codegen.generators"})
@javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_8)
public class CodeGenProcessor extends AbstractProcessor {

  private static final int JAVA= 0, RESOURCE = 1, OTHER = 2;
  private final static ObjectMapper mapper = new ObjectMapper();
  private static final Logger log = Logger.getLogger(CodeGenProcessor.class.getName());
  private File outputDirectory;
  private List<CodeGenerator> codeGenerators;
  private Map<String, GeneratedFile> generatedFiles = new HashMap<>();
  private Map<String, GeneratedFile> generatedResources = new HashMap<>();
  private Map<String, String> relocations = new HashMap<>();

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Arrays.asList(
        VertxGen.class,
        ProxyGen.class,
        DataObject.class,
        DataObject.class,
        ModuleGen.class
    ).stream().map(Class::getName).collect(Collectors.toSet());
  }

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    generatedFiles.clear();
    generatedResources.clear();
  }

  protected Predicate<CodeGenerator> filterGenerators() {
    String generatorsOption = processingEnv.getOptions().get("codegen.generators");
    if (generatorsOption == null) {
      generatorsOption = processingEnv.getOptions().get("codeGenerators");
      if (generatorsOption != null) {
        log.warning("Please use 'codegen.generators' option instead of 'codeGenerators' option");
      }
    }
    if (generatorsOption != null) {
      List<Pattern> wanted = Stream.of(generatorsOption.split(","))
        .map(String::trim)
        .map(Pattern::compile)
        .collect(Collectors.toList());
      return cg -> wanted.stream()
        .filter(p -> p.matcher(cg.name).matches())
        .findFirst()
        .isPresent();
    } else {
      return null;
    }
  }

  protected List<CodeGenerator> loadGenerators() {
    List<CodeGenerator> generators = new ArrayList<>();
    Enumeration<URL> descriptors = Collections.emptyEnumeration();
    try {
      descriptors = CodeGenProcessor.class.getClassLoader().getResources("codegen.json");
    } catch (IOException ignore) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Could not load code generator descriptors");
    }
    Set<String> templates = new HashSet<>();
    while (descriptors.hasMoreElements()) {
      URL descriptor = descriptors.nextElement();
      try (Scanner scanner = new Scanner(descriptor.openStream(), "UTF-8").useDelimiter("\\A")) {
        String s = scanner.next();
        ObjectNode obj = (ObjectNode) mapper.readTree(s);
        String name = obj.get("name").asText();
        ArrayNode generatorsCfg = (ArrayNode) obj.get("generators");
        for (JsonNode generator : generatorsCfg) {
          String kind = generator.get("kind").asText();
          JsonNode templateFilenameNode = generator.get("templateFilename");
          if (templateFilenameNode == null) {
            templateFilenameNode = generator.get("templateFileName");
          }
          String templateFilename = templateFilenameNode.asText();
          JsonNode filenameNode = generator.get("filename");
          if (filenameNode == null) {
            filenameNode = generator.get("fileName");
          }
          String filename = filenameNode.asText();
          boolean incremental = generator.has("incremental") && generator.get("incremental").asBoolean();
          if (!templates.contains(templateFilename)) {
            templates.add(templateFilename);
            generators.add(new CodeGenerator(name, kind, incremental, filename, templateFilename));
          }
        }
      } catch (Exception e) {
        String msg = "Could not load code generator " + descriptor;
        log.log(Level.SEVERE, msg, e);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg);
      }
    }
    return generators;
  }

  private Collection<CodeGenerator> getCodeGenerators() {
    if (codeGenerators == null) {
      String outputDirectoryOption = processingEnv.getOptions().get("codegen.output");
      if (outputDirectoryOption == null) {
        outputDirectoryOption = processingEnv.getOptions().get("outputDirectory");
        if (outputDirectoryOption != null) {
          log.warning("Please use 'codegen.output' option instead of 'outputDirectory' option");
        }
      }
      if (outputDirectoryOption != null) {
        outputDirectory = new File(outputDirectoryOption);
        if (!outputDirectory.exists()) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Output directory " + outputDirectoryOption + " does not exist");
        }
        if (!outputDirectory.isDirectory()) {
          processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Output directory " + outputDirectoryOption + " is not a directory");
        }
      }
      List<CodeGenerator> generators = loadGenerators();
      Predicate<CodeGenerator> filter = filterGenerators();
      if (filter != null) {
        generators = generators.stream().filter(filter).collect(Collectors.toList());
      }
      generators.forEach(gen -> {
        Template template = new Template(gen.templateFilename);
        template.setOptions(processingEnv.getOptions());
        gen.template = template;
        gen.filenameExpr = MVEL.compileExpression(gen.filename);
        log.info("Loaded " + gen.name + " code generator");
      });
      relocations = processingEnv.getOptions()
        .entrySet()
        .stream()
        .filter(e -> e.getKey().startsWith("codegen.output."))
        .collect(Collectors.toMap(
          e -> e.getKey().substring("codegen.output.".length()),
          Map.Entry::getValue)
        );

      codeGenerators = generators;
    }
    return codeGenerators;
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

    if (!roundEnv.processingOver()) {
      Collection<CodeGenerator> codeGenerators = getCodeGenerators();

      if (!roundEnv.errorRaised()) {
        CodeGen codegen = new CodeGen(processingEnv, roundEnv);
        Map<String, GeneratedFile> generatedClasses = new HashMap<>();

        // Generate source code
        codegen.getModels().forEach(entry -> {
          try {
            Model model = entry.getValue();
            Map<String, Object> vars = new HashMap<>();
            vars.put("helper", new Helper());
            vars.put("options", processingEnv.getOptions());
            vars.put("fileSeparator", File.separator);
            vars.put("fqn", model.getFqn());
            vars.put("module", model.getModule());
            vars.put("model", model);
            vars.putAll(model.getVars());
            vars.putAll(ClassKind.vars());
            vars.putAll(MethodKind.vars());
            vars.putAll(Case.vars());
            for (CodeGenerator codeGenerator : codeGenerators) {
              vars.putAll(TypeNameTranslator.vars(codeGenerator.name));
              if (codeGenerator.kind.equals(model.getKind())) {
                String relativeName = (String) MVEL.executeExpression(codeGenerator.filenameExpr, vars);
                if (relativeName != null) {

                  int kind;
                  if (relativeName.endsWith(".java") && !relativeName.contains("/")) {
                    String relocation = relocations.get(codeGenerator.name);
                    if (relocation != null) {
                      kind = OTHER;
                      relativeName = relocation + '/' +
                        relativeName.substring(0, relativeName.length() - ".java".length()).replace('.', '/') + ".java";
                    } else {
                      kind = JAVA;
                    }
                  } else if (relativeName.startsWith("resources/")) {
                    kind = RESOURCE;
                  } else {
                    kind = OTHER;
                  }
                  if (kind == JAVA) {
                    // Special handling for .java
                    String fqn = relativeName.substring(0, relativeName.length() - ".java".length());
                    // Avoid to recreate the same file (this may happen as we unzip and recompile source trees)
                    if (processingEnv.getElementUtils().getTypeElement(fqn) != null) {
                      continue;
                    }
                    List<ModelProcessing> processings = generatedClasses.computeIfAbsent(fqn, GeneratedFile::new);
                    processings.add(new ModelProcessing(model, codeGenerator));
                  } else if (kind == RESOURCE) {
                    relativeName = relativeName.substring("resources/".length());
                    List<ModelProcessing> processings = generatedResources.computeIfAbsent(relativeName, GeneratedFile::new);
                    processings.add(new ModelProcessing(model, codeGenerator));
                  } else {
                    List<ModelProcessing> processings = generatedFiles.computeIfAbsent(relativeName, GeneratedFile::new);
                    processings.add(new ModelProcessing(model, codeGenerator));
                  }
                }
              }
            }
          } catch (GenException e) {
            reportGenException(e);
          } catch (Exception e) {
            reportException(e, entry.getKey());
          }
        });

        // Generate classes
        generatedClasses.values().forEach(generated -> {
          try {
            String content = generated.generate();
            if (content.length() > 0) {
              JavaFileObject target = processingEnv.getFiler().createSourceFile(generated.uri);
              try (Writer writer = target.openWriter()) {
                writer.write(content);
              }
              log.info("Generated model " + generated.get(0).model.getFqn() + ": " + generated.uri);
            }
          } catch (GenException e) {
            reportGenException(e);
          } catch (Exception e) {
            reportException(e, generated.get(0).model.getElement());
          }
        });
      }
    } else {

      // Generate resources
      for (GeneratedFile generated : generatedResources.values()) {
        try {
          String content = generated.generate();
          if (content.length() > 0) {
            try (Writer w = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", generated.uri).openWriter()) {
              w.write(content);
            }
            boolean createSource;
            try {
              processingEnv.getFiler().getResource(StandardLocation.SOURCE_OUTPUT, "", generated.uri);
              createSource = true;
            } catch (FilerException e) {
              // SOURCE_OUTPUT == CLASS_OUTPUT
              createSource = false;
            }
            if (createSource) {
              try (Writer w = processingEnv.getFiler().createResource(StandardLocation.SOURCE_OUTPUT, "", generated.uri).openWriter()) {
                w.write(content);
              }
            }
            log.info("Generated model " + generated.get(0).model.getFqn() + ": " + generated.uri);
          }
        } catch (GenException e) {
          reportGenException(e);
        } catch (Exception e) {
          reportException(e, generated.get(0).model.getElement());
        }
      }
      // Generate files
      if (outputDirectory != null) {
        generatedFiles.values().forEach(generated -> {
          File file = new File(outputDirectory, generated.uri);
          Helper.ensureParentDir(file);
          String content = generated.generate();
          if (content.length() > 0) {
            try (FileWriter fileWriter = new FileWriter(file)) {
              fileWriter.write(content);
            } catch (GenException e) {
              reportGenException(e);
            } catch (Exception e) {
              reportException(e, generated.get(0).model.getElement());
            }
            log.info("Generated model " + generated.get(0).model.getFqn() + ": " + generated.uri);
          }
        });
      }
    }
    return true;
  }

  private void reportGenException(GenException e) {
    String name = e.element.toString();
    if (e.element.getKind() == ElementKind.METHOD) {
      name = e.element.getEnclosingElement() + "#" + name;
    }
    String msg = "Could not generate model for " + name + ": " + e.msg;
    log.log(Level.SEVERE, msg, e);
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, e.element);
  }

  private void reportException(Exception e, Element elt) {
    String msg = "Could not generate element for " + elt + ": " + e.getMessage();
    log.log(Level.SEVERE, msg, e);
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, elt);
  }

  private static class ModelProcessing {
    final Model model;
    final CodeGenerator generator;
    public ModelProcessing(Model model, CodeGenerator generator) {
      this.model = model;
      this.generator = generator;
    }
  }

  private static class GeneratedFile extends ArrayList<ModelProcessing> {
    final String uri;
    final Map<String, Object> session = new HashMap<>();
    public GeneratedFile(String uri) {
      super();
      this.uri = uri;
    }

    @Override
    public boolean add(ModelProcessing modelProcessing) {
      if (!modelProcessing.generator.incremental) {
        clear();
      }
      return super.add(modelProcessing);
    }

    String generate() {
      Collections.sort(this, (o1, o2) ->
        o1.model.getElement().getSimpleName().toString().compareTo(
          o2.model.getElement().getSimpleName().toString()));
      int index = 0;
      StringBuilder buffer = new StringBuilder();
      for (int i = 0; i < size(); i++) {
        ModelProcessing processing = get(i);
        Map<String, Object> vars = new HashMap<>();
        vars.putAll(TypeNameTranslator.vars(processing.generator.name));
        if (processing.generator.incremental) {
          vars.put("incrementalIndex", index++);
          vars.put("incrementalSize", size());
          vars.put("session", session);
        }
        try {
          String part = processing.generator.template.render(processing.model, vars);
          if (part != null) {
            buffer.append(part);
          }
        } catch (GenException e) {
          throw e;
        } catch (Exception e) {
          GenException genException = new GenException(processing.model.getElement(), e.getMessage());
          genException.initCause(e);
          throw genException;
        }
      }
      return buffer.toString();
    }
  }
}
