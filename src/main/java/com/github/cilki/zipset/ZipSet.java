/******************************************************************************
 *                                                                            *
 *  Copyright 2019 Tyler Cook (https://github.com/cilki)                      *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *      http://www.apache.org/licenses/LICENSE-2.0                            *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 *                                                                            *
 *****************************************************************************/
package com.github.cilki.zipset;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * A ZipSet is lazy set of entries that can be converted into a zip file at any
 * time. The set can be mutated like other sets, but does not do any I/O until
 * {@code build} is called.<br>
 * <br>
 * 
 * This class is NOT thread safe. Mutating the ZipSet while any variant of
 * {@code build} is running may produce strange results! <br>
 * <br>
 * 
 * Example usage:
 * 
 * <pre>
 * // Create a new ZipSet from an existing zip file
 * ZipSet zip = new ZipSet(sourceZipFile);
 * 
 * // Lazily add some entries
 * zip.add("test.txt", "1234".getBytes());     // A byte array
 * zip.add("test.png", Paths.get("test.png")); // A file on the filesystem
 * zip.add("inner.zip", anotherZipSet);        // Another ZipSet()
 * 
 * // Lazily add an entry to a nested zip
 * zip.add("inner.zip!/123.txt", Paths.get("123.txt"))
 * 
 * // Exclude some entries
 * zip.sub("delete.txt")
 * zip.sub("inner.zip!/example.txt");
 * 
 * // Output a new zip file containing all modifications in a single sweep
 * zip.build(outputZipFile);
 * </pre>
 * 
 * @author cilki
 */
public class ZipSet {

	/**
	 * Represents an absolute zip entry path that could be nested.
	 * 
	 * @author cilki
	 */
	public static class EntryPath {

		/**
		 * The path elements.
		 */
		private String[] path;

		/**
		 * Build an {@link EntryPath} from the given path elements.
		 * 
		 * @param path The nested path
		 * @return A new path
		 */
		public static EntryPath get(String... path) {
			return new EntryPath(path);
		}

		/**
		 * Build an {@link EntryPath} from a URI-style string.
		 * 
		 * @param path The nested path
		 * @return A new path
		 */
		public static EntryPath parse(String path) {
			return get(path.split("!"));
		}

		private EntryPath(String... path) {
			this.path = Objects.requireNonNull(path);
			if (path.length < 1)
				throw new IllegalArgumentException();
		}

		/**
		 * Get whether the {@link EntryPath} is nested.
		 * 
		 * @return Whether the path is nested
		 */
		public boolean isNested() {
			return path.length > 1;
		}

		/**
		 * Build a new {@link EntryPath} that consists of the current path without the
		 * first element. This reduces the nesting by one.
		 * 
		 * @return An {@link EntryPath} representing the lower path
		 */
		public EntryPath toLowerPath() {
			if (isNested())
				return new EntryPath(Arrays.copyOfRange(path, 1, path.length));
			return this;
		}

		/**
		 * Get the uppermost element of the path.
		 * 
		 * @return The first path element
		 */
		public String getUpperPath() {
			return path[0];
		}
	}

	/**
	 * Zip entries specified by the user.
	 */
	private Map<String, Object> content;

	/**
	 * An optional zip file that
	 */
	private Path source;

	/**
	 * Build a new empty {@link ZipSet}.
	 */
	public ZipSet() {
		content = new HashMap<>();
	}

	/**
	 * Build a new {@link ZipSet} using the given file as a base.
	 * 
	 * @param zip A zip file
	 */
	public ZipSet(Path zip) {
		this();
		this.source = Objects.requireNonNull(zip);
	}

	/**
	 * Add the given file or directory to the ZipSet.
	 * 
	 * @param entry The absolute entry path
	 * @param file  The path to add
	 * @return {@code this}
	 */
	public ZipSet add(String entry, Path file) {
		Objects.requireNonNull(entry);
		Objects.requireNonNull(file);

		return add(EntryPath.parse(entry), file);
	}

	/**
	 * Add the given file or directory to the ZipSet.
	 * 
	 * @param entry The absolute entry path
	 * @param file  The path to add
	 * @return {@code this}
	 */
	public ZipSet add(EntryPath entry, Path file) {
		Objects.requireNonNull(entry);
		Objects.requireNonNull(file);

		return addEntry(entry, file);
	}

	/**
	 * Add an empty directory to the ZipSet.
	 * 
	 * @param entry The absolute entry path
	 * @return {@code this}
	 */
	public ZipSet add(String entry) {
		Objects.requireNonNull(entry);

		if (!entry.endsWith("/"))
			entry += "/";
		return add(entry, new byte[] {});
	}

	/**
	 * Add the given resource to the ZipSet.
	 * 
	 * @param entry    The absolute entry path
	 * @param resource The resource to add
	 * @return {@code this}
	 */
	public ZipSet add(String entry, byte[] resource) {
		Objects.requireNonNull(entry);
		Objects.requireNonNull(resource);

		return add(EntryPath.parse(entry), resource);
	}

	/**
	 * Add the given resource to the ZipSet.
	 * 
	 * @param entry    The absolute entry path
	 * @param resource The resource to add
	 * @return {@code this}
	 */
	public ZipSet add(EntryPath entry, byte[] resource) {
		Objects.requireNonNull(entry);
		Objects.requireNonNull(resource);

		return addEntry(entry, resource);
	}

	/**
	 * Add the given ZipSet to the ZipSet.
	 * 
	 * @param entry The absolute entry path
	 * @param zip   The ZipSet to add
	 * @return {@code this}
	 */
	public ZipSet add(EntryPath entry, ZipSet zip) {
		Objects.requireNonNull(entry);
		Objects.requireNonNull(zip);

		return addEntry(entry, zip);
	}

	/**
	 * Subtract the given entry from the ZipSet.
	 * 
	 * @param entry The absolute entry to remove
	 * @return {@code this}
	 */
	public ZipSet sub(String entry) {
		Objects.requireNonNull(entry);

		return sub(EntryPath.parse(entry));
	}

	/**
	 * Subtract the given entry from the ZipSet.
	 * 
	 * @param entry The absolute entry to remove
	 * @return {@code this}
	 */
	public ZipSet sub(EntryPath entry) {
		Objects.requireNonNull(entry);

		return addEntry(entry, null);
	}

	/**
	 * Add an entry to the content map.
	 * 
	 * @param entry The absolute entry to add
	 * @param o     The associated object
	 * @return {@code this}
	 */
	private ZipSet addEntry(EntryPath entry, Object o) {
		content.put(entry.getUpperPath(), entry.isNested() ? new ZipSet().addEntry(entry.toLowerPath(), o) : o);
		return this;
	}

	/**
	 * Build a new ZipSet containing the intersection of {@code this} and the given
	 * ZipSet.
	 * 
	 * @param zip The other ZipSet
	 * @return A new ZipSet
	 */
	public ZipSet intersect(ZipSet zip) {
		throw new RuntimeException("Not implemented");
	}

	/**
	 * Build a byte array containing the final zip.
	 * 
	 * @return A byte array containing the zip.
	 * @throws IOException
	 */
	public byte[] build() throws IOException {
		try (var out = new ByteArrayOutputStream()) {
			build(out);
			return out.toByteArray();
		}
	}

	/**
	 * Write the final zip to the given file.
	 * 
	 * @param output The file that will receive the zip
	 * @throws IOException
	 */
	public void build(Path output) throws IOException {
		Objects.requireNonNull(output);

		try (var out = Files.newOutputStream(output)) {
			build(out);
		}
	}

	/**
	 * Write the final zip to the given {@link OutputStream}.
	 * 
	 * @param out The stream that will receive the zip
	 * @throws IOException
	 */
	public void build(OutputStream out) throws IOException {
		try (var zip = new ZipOutputStream(out)) {

			// Copy the source file if one was specified
			if (source != null) {
				try (var zipIn = new ZipInputStream(Files.newInputStream(source))) {
					ZipEntry entry;
					while ((entry = zipIn.getNextEntry()) != null) {
						// If content contains the entry, it is either overwriting an entry from the
						// source file or explicitly excluding it
						if (!content.containsKey(entry.getName())) {
							zip.putNextEntry(entry);
							zipIn.transferTo(zip);
							zip.closeEntry();
						}
					}
				}
			}

			// Copy the rest of the entries
			for (Entry<String, Object> entry : content.entrySet()) {
				Object resource = entry.getValue();
				if (resource == null)
					continue;

				if (resource instanceof byte[]) {
					if (entry.getKey().endsWith("/")) {
						zip.putNextEntry(newDirectoryEntry(entry.getKey()));
						zip.closeEntry();
					} else {
						zip.putNextEntry(newFileEntry(entry.getKey()));
						zip.write((byte[]) resource);
						zip.closeEntry();
					}
				} else if (resource instanceof ZipSet) {
					zip.putNextEntry(newFileEntry(entry.getKey()));
					// TODO pass stream directly with the following call rather than building an
					// unnecessary byte array. The problem is that closing a nested ZipOutputStream
					// also closes all parent streams
					// ((ZipSet) resource).build(zip);
					zip.write(((ZipSet) resource).build());
					zip.closeEntry();
				} else if (resource instanceof Path)
					writePath(zip, (Path) resource, entry.getKey());
			}
		}
	}

	/**
	 * Write the given file or directory to the given zip stream.
	 * 
	 * @param zip  The zip to receive the files
	 * @param path The file or directory to write
	 * @param name The name of the file or directory
	 * @throws IOException
	 */
	private void writePath(ZipOutputStream zip, Path path, String name) throws IOException {
		if (Files.isDirectory(path)) {
			zip.putNextEntry(newDirectoryEntry(name));
			zip.closeEntry();
			for (Path p : Files.list(path).collect(Collectors.toList())) {
				writePath(zip, p, name + "/" + p.getFileName().toString());
			}
		} else {
			zip.putNextEntry(newFileEntry(name));
			Files.copy(path, zip);
			zip.closeEntry();
		}
	}

	/**
	 * Build a new {@link ZipEntry} for a directory.
	 * 
	 * @param name The absolute directory path
	 * @return A ZipEntry for the path
	 */
	private static ZipEntry newDirectoryEntry(String name) {
		if (name.lastIndexOf('/') == name.length() - 1)
			return new ZipEntry(name);
		return new ZipEntry(name + "/");
	}

	/**
	 * Build a new {@link ZipEntry} for a file.
	 * 
	 * @param name The absolute file path
	 * @return A ZipEntry for the path
	 */
	private static ZipEntry newFileEntry(String name) {
		if (name.lastIndexOf('/') == name.length() - 1)
			return new ZipEntry(name.substring(0, name.length() - 1));
		return new ZipEntry(name);
	}
}
