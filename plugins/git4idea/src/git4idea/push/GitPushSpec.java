/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package git4idea.push;

import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.VcsException;
import git4idea.GitBranch;
import git4idea.GitUtil;
import git4idea.branch.GitBranchPair;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author Kirill Likhodedov
 */
public class GitPushSpec {

  @NotNull private final GitRemote myRemote;
  @NotNull private final GitBranch mySource;
  @NotNull private final GitBranch myDest;

  GitPushSpec(@NotNull GitRemote remote, @NotNull GitBranch source, @NotNull GitBranch dest) {
    myRemote = remote;
    mySource = source;
    myDest = dest;
  }

  @NotNull
  public GitRemote getRemote() {
    return myRemote;
  }

  @NotNull
  public GitBranch getSource() {
    return mySource;
  }

  @NotNull
  public GitBranch getDest() {
    return myDest;
  }

  @Nullable
  private static GitBranchPair findSourceDestForBranch(GitRepository repository, GitBranch branch) throws VcsException {
    GitBranch trackedBranch = branch.tracked(repository.getProject(), repository.getRoot());
    if (trackedBranch != null) {
      return new GitBranchPair(branch, trackedBranch);
    }
    Pair<GitRemote,GitBranch> remoteAndBranch = GitUtil.findMatchingRemoteBranch(repository, branch);
    if (remoteAndBranch == null) {
      return null;
    }
    GitBranch matchingRemoteBranch = remoteAndBranch.getSecond();
    if (matchingRemoteBranch != null) {
      return new GitBranchPair(branch, matchingRemoteBranch);
    }
    return null;
  }

  @Override
  public String toString() {
    return myRemote + " " + mySource + "->" + myDest;
  }
}
