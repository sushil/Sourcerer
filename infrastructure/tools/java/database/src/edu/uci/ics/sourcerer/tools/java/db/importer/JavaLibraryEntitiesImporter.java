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
package edu.uci.ics.sourcerer.tools.java.db.importer;

import edu.uci.ics.sourcerer.tools.java.db.schema.ProjectsTable;
import edu.uci.ics.sourcerer.tools.java.model.extracted.io.ReaderBundle;
import edu.uci.ics.sourcerer.tools.java.model.types.Project;
import edu.uci.ics.sourcerer.tools.java.repo.model.extracted.ExtractedJarFile;
import edu.uci.ics.sourcerer.utils.db.sql.ConstantCondition;
import edu.uci.ics.sourcerer.utils.db.sql.SelectQuery;
import edu.uci.ics.sourcerer.utils.db.sql.SetStatement;
import edu.uci.ics.sourcerer.utils.db.sql.TypedQueryResult;


/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class JavaLibraryEntitiesImporter extends EntitiesImporter {
  private Iterable<? extends ExtractedJarFile> libraries;
  
  protected JavaLibraryEntitiesImporter(Iterable<? extends ExtractedJarFile> libraries) {
    super("Importing Java Library Entities");
    this.libraries = libraries;
  }

  @Override
  public void doImport() {
    try (SelectQuery projectState = exec.makeSelectQuery(ProjectsTable.TABLE)) {
      projectState.addSelect(ProjectsTable.PATH);
      projectState.addSelect(ProjectsTable.PROJECT_ID);
      ConstantCondition<String> equalsName = ProjectsTable.NAME.compareEquals();
      projectState.andWhere(equalsName.and(ProjectsTable.PROJECT_TYPE.compareEquals(Project.JAVA_LIBRARY)));
      
      SetStatement updateState = exec.makeSetStatement(ProjectsTable.TABLE);
      updateState.addAssignment(ProjectsTable.PATH, "END_ENTITY");
      ConstantCondition<Integer> equalsID = ProjectsTable.PROJECT_ID.compareEquals();
      updateState.andWhere(equalsID);
      
      for (ExtractedJarFile lib : libraries) {
        String name = lib.getProperties().NAME.getValue();
        task.start("Importing " + name + "'s entities");
        
        task.start("Verifying import suitability");
        boolean shouldImport = true;
        if (lib.getProperties().EXTRACTED.getValue()) {
          equalsName.setValue(name);
          TypedQueryResult result = projectState.select();
          if (result.hasNext()) {
            String state = result.getResult(ProjectsTable.PATH);
            if ("END_ENTITY".equals(state) || state == null) {
              task.report("Entity import already completed... skipping");
              shouldImport = false;
            } else {
              task.start("Deleting incomplete import");
              deleteProject(result.getResult(ProjectsTable.PROJECT_ID));
            }
          }
        } else {
          task.report("Extraction not completed... skipping");
          shouldImport = false;
        }
        task.finish();
        
        if (shouldImport) {
          task.start("Inserting project");
          Integer projectID = exec.insertWithKey(ProjectsTable.TABLE.makeInsert(lib));
          task.finish();
          
          ReaderBundle reader = new ReaderBundle(lib.getExtractionDir().toFile());
          
          insert(reader, projectID);
          
          equalsID.setValue(projectID);
          updateState.execute();
        }
        
        task.finish();
      }
    }
  }
}
