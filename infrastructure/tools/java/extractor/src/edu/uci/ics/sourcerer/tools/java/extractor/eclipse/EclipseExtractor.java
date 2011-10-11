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
package edu.uci.ics.sourcerer.tools.java.extractor.eclipse;

import static edu.uci.ics.sourcerer.util.io.Logging.logger;

import java.io.Closeable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IOpenable;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.core.BinaryType;

import edu.uci.ics.sourcerer.tools.java.extractor.bytecode.ASMExtractor;
import edu.uci.ics.sourcerer.tools.java.extractor.io.WriterBundle;
import edu.uci.ics.sourcerer.util.io.IOUtils;
import edu.uci.ics.sourcerer.util.io.TaskProgressLogger;

/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class EclipseExtractor implements Closeable {
  private final TaskProgressLogger task;
  private final ASTParser parser;
  private final WriterBundle writers;
  private final ReferenceExtractorVisitor visitor;
  private final ASMExtractor asmExtractor;
  private final ClassFileExtractor eclipseExtractor;
  
  public EclipseExtractor(TaskProgressLogger task, WriterBundle bundle) {
    this(task, bundle, null);
  }
  
  public EclipseExtractor(TaskProgressLogger task, WriterBundle writers, ASMExtractor asmExtractor) {
    this.task = task;
    this.writers = writers;
    parser = ASTParser.newParser(AST.JLS4);
    visitor = new ReferenceExtractorVisitor(writers);
    if (asmExtractor == null) {
      this.asmExtractor = null;
      this.eclipseExtractor = new ClassFileExtractor(writers);
    } else {
      this.asmExtractor = asmExtractor;
      this.eclipseExtractor = null;
    }
  }
  
  @Override
  public void close() {
    IOUtils.close(writers);
  }
  
  private void extractClassFile(IClassFile classFile) {
    if (asmExtractor == null) {
      eclipseExtractor.extractClassFile(classFile);
    } else {
      try {
        asmExtractor.extract(classFile.getParent().getElementName(), classFile.getElementName(), classFile.getBytes());
      } catch (JavaModelException e) {
        logger.log(Level.SEVERE, "Unable to get bytecode for " + classFile.getElementName(), e);
      }
    }
  }
  @SuppressWarnings("restriction")
  public boolean extractClassFiles(Collection<IClassFile> classFiles) {
    task.start("Extracting " + classFiles.size() + " class files", "class files extracted", 500);
    boolean oneWithSource = false;
    
    
    
    Map<IOpenable, Collection<IClassFile>> memberMap = new HashMap<>();;
    Collection<IOpenable> sourceFailed = new LinkedList<>();
    
    for (IClassFile classFile : classFiles) {
      task.progress();
      try {
        IBuffer buffer = classFile.getBuffer();
        if (buffer == null || buffer.getLength() == 0) {
          extractClassFile(classFile);
        } else {
          IType type = classFile.getType();
          if (type.isMember() || type.isAnonymous() || type.isLocal()) {
            IOpenable owner = buffer.getOwner();
            Collection<IClassFile> members = memberMap.get(owner);
            if (members == null) {
              members = new LinkedList<>();
              memberMap.put(owner, members);
            }
            members.add(classFile);
          } else {
            // Handle Eclipse issue with GSSUtil
            if ("sun.security.jgss.GSSUtil".equals(type.getFullyQualifiedName())) {
              extractClassFile(classFile);
              continue;
            }
            // Handle multiple top-level types
            {
              BinaryType bType = (BinaryType) type;
              String sourceFile = type.getPackageFragment().getElementName() + "." + bType.getSourceFileName(null);
              String fqn = classFile.getType().getFullyQualifiedName() + ".java";
              if (!fqn.equals(sourceFile)) {
                continue;
              }
            }
            parser.setStatementsRecovery(true);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setSource(classFile);
              
            CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            boolean foundProblem = false;
            // start by checking for a "public type" error
            // just skip this unit in if one is found 
            for (IProblem problem : unit.getProblems()) {
              if (problem.isError() && problem.getID() == IProblem.PublicClassMustMatchFileName) {
                foundProblem = true;
              }
            }
            if (foundProblem) {
              logger.log(Level.WARNING, "Giving up on " + classFile.getElementName());
              continue;
            }
              
            boolean trouble = checkForMissingTypes(unit);
            if (trouble) {
              sourceFailed.add(classFile);
              extractClassFile(classFile);
            } else {
              try {
                unit.accept(visitor);
                oneWithSource = true;
              } catch (Exception e) {
                logger.log(Level.SEVERE, "Error in extracting " + classFile.getElementName(), e);
                for (IProblem problem : unit.getProblems()) {
                  if (problem.isError()) {
                    logger.log(Level.SEVERE, "Error in source for class file (" + classFile.getElementName() + "): " + problem.getMessage());
                  }
                }
                sourceFailed.add(classFile);
                extractClassFile(classFile);
              }
            }
          }
        }
      } catch (JavaModelException e) {
        sourceFailed.add(classFile);
        extractClassFile(classFile);
      }
    }
    
    for (IOpenable failed : sourceFailed) {
      Collection<IClassFile> members = memberMap.get(failed);
      if (members != null) {
        for (IClassFile classFile : members) {
          extractClassFile(classFile);
        }
      }
    }
    task.finish();
    return oneWithSource;
  }
  
  public void extractSourceFiles(Collection<IFile> sourceFiles) {
    task.start("Extracting " + sourceFiles.size() + " source files", "sources files extracted", 500);

    ReferenceExtractorVisitor visitor = new ReferenceExtractorVisitor(writers);
    for (IFile source : sourceFiles) {
      task.progress();
      ICompilationUnit icu = JavaCore.createCompilationUnitFrom(source);

      parser.setStatementsRecovery(true);
      parser.setResolveBindings(true);
      parser.setBindingsRecovery(true);
      parser.setSource(icu);
  
      CompilationUnit unit = null;
      try {
        unit = (CompilationUnit)parser.createAST(null);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Error in creating AST for " + source.getName(), e);
        continue;
      }
  
      visitor.setBindingFreeMode(checkForMissingTypes(unit));
      
      try {
        visitor.setCompilationUnitSource(icu.getSource());
        unit.accept(visitor);
      } catch (Exception e) {
        logger.log(Level.SEVERE, "Error in extracting " + source.getName(), e);
      }
    }
    task.finish();
  }
  
  private boolean checkForMissingTypes(CompilationUnit unit) {
    // Check for the classpath problem
    for (IProblem problem : unit.getProblems()) {
      if (problem.isError()) {
        if (problem.getID() == IProblem.IsClassPathCorrect) {
          return true;
        }
      }
    }
    return false;
  }
}
