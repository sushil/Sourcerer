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
package edu.uci.ics.sourcerer.tools.java.utilization.model.jar;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import edu.uci.ics.sourcerer.util.MutableSingletonMap;

/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class Versions implements Iterable<Version> {
  private JarSet jars;
  private Map<Fingerprint, Version> versions;
  
  private Versions() {
    jars = JarSet.create();
    versions = Collections.emptyMap();
  }
  
  public static Versions create() {
    return new Versions();
  }
  
  public void add(Fingerprint fingerprint, Jar jar) {
    if (versions.isEmpty()) {
      jars = jars.add(jar);
      versions = MutableSingletonMap.create(fingerprint, Version.create(fingerprint, jar));
    } else if (versions.size() == 1) {
      Version version = versions.get(fingerprint);
      if (version == null) {
        versions = new HashMap<>(versions);
        versions.put(fingerprint, Version.create(fingerprint, jar));
        jars = jars.add(jar);
      } else {
        jars = jars.add(jar);
        version.addJar(jar);
      }
    } else {
      Version version = versions.get(fingerprint);
      if (version == null) {
        versions.put(fingerprint, Version.create(fingerprint, jar));
      } else {
        version.addJar(jar);
      }
      jars = jars.add(jar);
    }
  }
  
  public int getCount() {
    return versions.size();
  }
  
  public JarSet getJars() {
    return jars;
  }
  
  @Override
  public Iterator<Version> iterator() {
    return versions.values().iterator();
  }
}
