/* 
 * Sourcerer: an infrastructure for large-scale source code analysis.
 * Copyright (C) by contributors. See CONTRIBUTORS.txt for full list.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package edu.uci.ics.sourcerer.tools.java.extractor;

import static edu.uci.ics.sourcerer.util.io.logging.Logging.logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IClassFile;

import edu.uci.ics.sourcerer.tools.java.extractor.bytecode.ASMExtractor;
import edu.uci.ics.sourcerer.tools.java.extractor.eclipse.EclipseExtractor;
import edu.uci.ics.sourcerer.tools.java.extractor.eclipse.EclipseUtils;
import edu.uci.ics.sourcerer.tools.java.extractor.io.FileWriter;
import edu.uci.ics.sourcerer.tools.java.extractor.io.UsedJarWriter;
import edu.uci.ics.sourcerer.tools.java.extractor.io.WriterBundle;
import edu.uci.ics.sourcerer.tools.java.extractor.missing.MissingTypeCollection;
import edu.uci.ics.sourcerer.tools.java.extractor.missing.MissingTypeIdentifier;
import edu.uci.ics.sourcerer.tools.java.model.types.File;
import edu.uci.ics.sourcerer.tools.java.repo.model.JarFile;
import edu.uci.ics.sourcerer.tools.java.repo.model.JarProperties;
import edu.uci.ics.sourcerer.tools.java.repo.model.JavaFile;
import edu.uci.ics.sourcerer.tools.java.repo.model.JavaFileSet;
import edu.uci.ics.sourcerer.tools.java.repo.model.JavaProject;
import edu.uci.ics.sourcerer.tools.java.repo.model.JavaRepository;
import edu.uci.ics.sourcerer.tools.java.repo.model.JavaRepositoryFactory;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ExtractedJarProperties;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ExtractedJavaProjectProperties;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ModifiableExtractedJarFile;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ModifiableExtractedJavaProject;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ModifiableExtractedJavaRepository;
import edu.uci.ics.sourcerer.util.io.IOUtils;
import edu.uci.ics.sourcerer.util.io.arguments.Argument;
import edu.uci.ics.sourcerer.util.io.arguments.BooleanArgument;
import edu.uci.ics.sourcerer.util.io.logging.Logging;
import edu.uci.ics.sourcerer.util.io.logging.TaskProgressLogger;

/**
 * @author Joel Ossher 
 *
 */
public class Extractor {
  private Extractor() {}

  public static enum JarType {
    LIBRARY,
    PROJECT,
    MAVEN;
    
    @Override
    public String toString() {
      return name().toLowerCase();
    }
  };
  
  public static enum ExtractionMethod {
    ASM("ASM", true, false),
    ECLIPSE("Eclipse", false, true),
    ASM_ECLIPSE("ASM and Eclipse", true, true);
    
    private final String text;
    private final boolean withASM;
    private final boolean withEclipse;
    
    private ExtractionMethod(String text, boolean withASM, boolean withEclipse) {
      this.text = text;
      this.withASM = withASM;
      this.withEclipse = withEclipse;
    }
    
    @Override
    public String toString() {
      return text;
    }
  }
  
  public static void extractJars(JarType jarType, ExtractionMethod method) {
    TaskProgressLogger task = TaskProgressLogger.get();
    
    // Load the input repository
    JavaRepository repo = JavaRepositoryFactory.INSTANCE.loadJavaRepository(JavaRepositoryFactory.INPUT_REPO);
    // Load the output repository
    ModifiableExtractedJavaRepository extracted = JavaRepositoryFactory.INSTANCE.loadModifiableExtractedJavaRepository(JavaRepositoryFactory.OUTPUT_REPO);
    
    task.start("Performing " + jarType.toString() + " jar extraction with " + method);
    
    task.start("Loading jar files");
    Collection<? extends JarFile> jars = null;
    switch (jarType) {
      case LIBRARY: jars = repo.getLibraryJarFiles(); break;
      case MAVEN:   jars = repo.getMavenJarFiles(); break;
      case PROJECT: jars = repo.getProjectJarFiles(); break;
    }
    task.finish();
    
    task.start("Extracting " + jars.size() + " jar files", "jar files extracted", 1);
    
    // Only do this initialization once
    if (method.withEclipse && jarType == JarType.LIBRARY) {
      task.start("Initializing eclipse project");
      EclipseUtils.initializeLibraryProject(jars);
      task.finish();
    }
    
    for (JarFile jar : jars) {
      task.progress("Extracting " + jar + " (%d of " + jars.size() + ")");
      ModifiableExtractedJarFile extractedJar = extracted.getMatchingJarFile(jar);
      if (Boolean.TRUE.equals(extractedJar.getProperties().EXTRACTED.getValue())) {
        if (Main.FORCE_REDO.getValue()) {
          extractedJar.reset(jar);
        } else {
          task.report("Library already extracted");
          continue;
        }
      } 
      
      // Set up logging
      Logging.addFileLogger(extractedJar.getExtractionDir().toFile());

      if (method.withEclipse && jarType != JarType.LIBRARY) {
        task.start("Initializing eclipse project");
        EclipseUtils.initializeJarProject(jar);
        task.finish();
      }

      // Set up the writer bundle
      WriterBundle writers = new WriterBundle(extractedJar.getExtractionDir().toFile());
    
      ASMExtractor asmExtractor = null;
      if (method.withASM) {
        asmExtractor = new ASMExtractor(writers);
      }
      boolean hasSource = false;
      if (method.withEclipse) {
        task.start("Getting class files");
        Collection<IClassFile> classFiles = EclipseUtils.getClassFiles(jar);
        task.finish();

        // Extract
        try (EclipseExtractor extractor = new EclipseExtractor(writers, asmExtractor)) {
          hasSource = extractor.extractClassFiles(classFiles);
        }
      } else {
        asmExtractor.extractJar(jar.getFile().toFile());
      }
      IOUtils.close(asmExtractor);
        
      // Write the properties files
      ExtractedJarProperties properties = extractedJar.getProperties();
      properties.EXTRACTED.setValue(true);
      properties.HAS_SOURCE.setValue(hasSource);
      properties.save(); 

      // End the error logging
      Logging.removeFileLogger(extractedJar.getExtractionDir().toFile());
    }
    task.finish();
  }
  
  public static final Argument<Boolean> INCLUDE_PROJECT_JARS = new BooleanArgument("include-project-jars", true, "Should projects jars be added to the classpath?");
  
  public static void extractProjects() {
    TaskProgressLogger task = TaskProgressLogger.get();
    
    task.start("Performing project extraction with Eclipse");
    
    // Load the input repository
    JavaRepository repo = JavaRepositoryFactory.INSTANCE.loadJavaRepository(JavaRepositoryFactory.INPUT_REPO);
    // Load the output repository
    ModifiableExtractedJavaRepository extracted = JavaRepositoryFactory.INSTANCE.loadModifiableExtractedJavaRepository(JavaRepositoryFactory.OUTPUT_REPO);

    
    task.start("Loading projects");
    Collection<? extends JavaProject> projects = repo.getProjects();
    task.finish();
    
    task.start("Extracting " + projects.size() + " projects", "projects extracted", 1);
    for (JavaProject project : projects) {
      task.progress("Extracting " + project + " (%d of " + projects.size() + ")");
      ModifiableExtractedJavaProject extractedProject = extracted.getMatchingProject(project);
      if (Boolean.TRUE.equals(extractedProject.getProperties().EXTRACTED.getValue())) {
        if (Main.FORCE_REDO.getValue()) {
          extractedProject.reset(project);
        } else {
          task.report("Project already extracted");
          continue;
        }
      }
      
      // Set up logging
      Logging.addFileLogger(extractedProject.getExtractionDir().toFile());
      
      task.report("Getting project contents");
      JavaFileSet files = project.getContent();
     
      if (INCLUDE_PROJECT_JARS.getValue()) {
        task.start("Loading " + files.getJarFiles().size() + " jar files into classpath");
        EclipseUtils.initializeProject(files.getJarFiles());
        task.finish();
      } else {
        EclipseUtils.initializeProject(Collections.<JarFile>emptyList());
      }
      
      task.start("Loading " + files.getFilteredJavaFiles().size() + " java files into project");
      Map<JavaFile, IFile> sourceFiles = EclipseUtils.loadFilesIntoProject(files.getFilteredJavaFiles());
      task.finish();
      
      // Set up the writer bundle
      WriterBundle bundle = new WriterBundle(extractedProject.getExtractionDir().toFile());

      // Write out the jars
      // Write out the used jars
      FileWriter fileWriter = bundle.getFileWriter();
      UsedJarWriter jarWriter = bundle.getUsedJarWriter();
      for (JarFile jar : files.getJarFiles()) {
        JarProperties props = jar.getProperties();
        fileWriter.writeFile(File.JAR, props.NAME.getValue(), null, props.HASH.getValue());
        jarWriter.writeUsedJar(props.HASH.getValue());
      }

      // Extract
      try (EclipseExtractor extractor = new EclipseExtractor(bundle)) {
        extractor.extractSourceFiles(sourceFiles);
      }
      
      // Write the properties files
      ExtractedJavaProjectProperties properties = extractedProject.getProperties();
      properties.EXTRACTED.setValue(true);
      properties.save();
      
      // End the error logging
      Logging.removeFileLogger(extractedProject.getExtractionDir().toFile());
    }
    task.finish();
    
    task.finish();
  }
}
