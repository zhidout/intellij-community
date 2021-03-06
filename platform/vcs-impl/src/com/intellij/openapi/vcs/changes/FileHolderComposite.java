/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vcs.changes;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class FileHolderComposite implements FileHolder {
  private final Map<HolderType, FileHolder> myHolders = new HashMap<>();

  public FileHolderComposite(Project project) {
    add(new VirtualFileHolder(project, HolderType.UNVERSIONED));
    add(new SwitchedFileHolder(project, HolderType.ROOT_SWITCH));
    add(new SwitchedFileHolder(project, HolderType.SWITCHED));
    add(new VirtualFileHolder(project, HolderType.MODIFIED_WITHOUT_EDITING));
    add(new IgnoredFilesCompositeHolder(project));
    add(new VirtualFileHolder(project, HolderType.LOCKED));
    add(new LogicallyLockedHolder(project));
    add(new DeletedFilesHolder());
  }

  private FileHolderComposite(FileHolderComposite holder) {
    for (FileHolder fileHolder : holder.myHolders.values()) {
      addCopy(fileHolder);
    }
  }

  private void add(@NotNull FileHolder fileHolder) {
    myHolders.put(fileHolder.getType(), fileHolder);
  }

  private void addCopy(@NotNull FileHolder fileHolder) {
    myHolders.put(fileHolder.getType(), fileHolder.copy());
  }


  @Override
  public void cleanAll() {
    for (FileHolder holder : myHolders.values()) {
      holder.cleanAll();
    }
  }

  @Override
  public void cleanAndAdjustScope(VcsModifiableDirtyScope scope) {
    for (FileHolder holder : myHolders.values()) {
      holder.cleanAndAdjustScope(scope);
    }
  }


  @Override
  public FileHolderComposite copy() {
    return new FileHolderComposite(this);
  }

  public FileHolder get(HolderType type) {
    return myHolders.get(type);
  }

  public VirtualFileHolder getVFHolder(HolderType type) {
    return (VirtualFileHolder)myHolders.get(type);
  }

  public IgnoredFilesCompositeHolder getIgnoredFileHolder() {
    return (IgnoredFilesCompositeHolder)myHolders.get(HolderType.IGNORED);
  }

  public LogicallyLockedHolder getLogicallyLockedFileHolder() {
    return (LogicallyLockedHolder)myHolders.get(HolderType.LOGICALLY_LOCKED);
  }

  public SwitchedFileHolder getRootSwitchFileHolder() {
    return (SwitchedFileHolder)myHolders.get(HolderType.ROOT_SWITCH);
  }

  public SwitchedFileHolder getSwitchedFileHolder() {
    return (SwitchedFileHolder)myHolders.get(HolderType.SWITCHED);
  }

  public DeletedFilesHolder getDeletedFileHolder() {
    return (DeletedFilesHolder)myHolders.get(HolderType.DELETED);
  }


  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final FileHolderComposite another = (FileHolderComposite)o;
    return myHolders.equals(another.myHolders);
  }

  @Override
  public int hashCode() {
    return myHolders.hashCode();
  }


  @Override
  public HolderType getType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void notifyVcsStarted(AbstractVcs vcs) {
    for (FileHolder fileHolder : myHolders.values()) {
      fileHolder.notifyVcsStarted(vcs);
    }
  }
}
