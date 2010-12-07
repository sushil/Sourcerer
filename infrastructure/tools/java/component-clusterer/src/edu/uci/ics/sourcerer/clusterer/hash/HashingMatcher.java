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
package edu.uci.ics.sourcerer.clusterer.hash;

import java.util.Collection;

import edu.uci.ics.sourcerer.util.Helper;

/**
 * @author Joel Ossher (jossher@uci.edu)
 */
public class HashingMatcher {
  private String md5;
  private String sha;
  private long length;

  private Collection<String> projects;
  private Collection<String> paths;
  
  protected HashingMatcher() {
    projects = Helper.newHashSet();
    paths = Helper.newLinkedList();
  }
  
  protected void setValues(String md5, String sha, long length) {
    this.md5 = md5;
    this.sha = sha;
    this.length = length;
  }
  
  protected HashingMatcher copy() {
    HashingMatcher retval = new HashingMatcher();
    retval.setValues(md5, sha, length);
    return retval;
  }
  
  public String getMD5() {
    return md5;
  }
  
  public long getLength() {
    return length;
  }
  
  public int getProjectCount() {
    return projects.size();
  }
  
  public void add(String project, String path) {
    projects.add(project);
    paths.add(path);
  }
  
  public int hashCode() {
    return md5.hashCode();
  }
  
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else if (o instanceof HashingMatcher) {
      HashingMatcher other = (HashingMatcher) o;
      return md5.equals(other.md5) && sha.equals(other.sha) && length == other.length;
    } else {
      return false;
    }
  }
  
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append(projects.size());
    for (String path : paths) {
      builder.append(" ").append(path);
    }
    return builder.toString();
  }
}
