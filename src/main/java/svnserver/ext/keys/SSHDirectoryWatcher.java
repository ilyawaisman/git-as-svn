/*
 * This file is part of git-as-svn. It is subject to the license terms
 * in the LICENSE file found in the top-level directory of this distribution
 * and at http://www.gnu.org/licenses/gpl-2.0.html. No part of git-as-svn,
 * including this file, may be copied, modified, propagated, or distributed
 * except according to the terms contained in the LICENSE file.
 */
package svnserver.ext.keys;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import svnserver.Loggers;
import svnserver.context.Shared;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.WatchEvent.Kind;
import java.util.HashSet;

/**
 * SSHDirectoryWatcher.
 *
 * @author Andrew Thornton <zeripath@users.noreply.github.com>
 */
final class SSHDirectoryWatcher extends Thread implements Shared {
  @NotNull
  private static final Kind<?>[] KINDS = new Kind<?>[]{StandardWatchEventKinds.ENTRY_CREATE,
      StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE};
  @NotNull
  private static final String AUTHORIZED_KEYS = "authorized_keys";

  @NotNull
  private static final Logger log = Loggers.misc;
  @NotNull
  private final WatchService watchService;
  @Nullable
  private final KeysMapper mapper;
  @NotNull
  private final Path basePath;
  @NotNull
  private final Path realSSHPath;
  @NotNull
  private final String originalAppPath;
  @NotNull
  private final String svnServePath;

  SSHDirectoryWatcher(@NotNull KeysConfig config, @Nullable KeysMapper mapper) {
    this.originalAppPath = config.getOriginalAppPath();
    this.svnServePath = config.getSvnservePath();
    this.mapper = mapper;
    try {
      this.basePath = Paths.get(config.getShadowSSHDirectory()).toAbsolutePath();
      this.realSSHPath = Paths.get(config.getRealSSHDirectory()).toAbsolutePath();
      this.watchService = FileSystems.getDefault().newWatchService();
      this.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public void run() {
    try {
      // Run this first.
      mungeAuthorizedKeys();
      basePath.register(watchService, KINDS);
      while (!isInterrupted()) {
        WatchKey key = watchService.take();
        if (isInterrupted()) {
          break;
        }
        for (final WatchEvent<?> event : key.pollEvents()) {
          Object context = event.context();
          if (!(context instanceof Path)) {
            continue;
          }
          Path p = (Path) context;
          if (!p.toString().equals(AUTHORIZED_KEYS)) {
            continue;
          }
          // OK we're looking at authorized_keys - munge it!
          mungeAuthorizedKeys();
        }

        if (!key.reset()) {
          key.cancel();
          break;
        }
      }
    } catch (InterruptedException e) {
      // noop
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void mungeAuthorizedKeys() throws IOException {
    Path authPath = basePath.resolve(AUTHORIZED_KEYS);
    Path realAuthPath = realSSHPath.resolve(AUTHORIZED_KEYS);
    log.info("Processing the authorized_keys file: {}", authPath.toString());

    HashSet<String> keysSet = new HashSet<>();

    try (
        BufferedReader reader = Files.newBufferedReader(authPath);
        BufferedWriter writer = Files.newBufferedWriter(realAuthPath)
    ) {
      reader.lines().map(s -> {
        if (s.contains(originalAppPath)) {
          int indexOfKey = s.indexOf("key-");
          keysSet.add(s.substring(indexOfKey, s.indexOf(' ', indexOfKey)));
          return s.replace(originalAppPath, svnServePath);
        } else {
          return s;
        }
      }).forEach(s -> {
        try {
          writer.write(s);
          writer.write('\n');
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }

    log.info("Found {} keys", keysSet.size());

    // OK now we know about which keys are there.
    // So we tell our keys mapper...
    if (this.mapper != null) {
      this.mapper.setKeys(keysSet);
    }
  }

  public void close() {
    this.interrupt();
  }
}