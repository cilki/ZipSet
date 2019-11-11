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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
public final class ZipSet implements Serializable {

	/**
	 * Represents an absolute zip entry path that could be nested.
	 */
	public static final class EntryPath {

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

		/**
		 * The path elements.
		 */
		private String[] path;

		private EntryPath(String... path) {
			Objects.requireNonNull(path);

			if (path.length < 1)
				throw new IllegalArgumentException("An EntryPath cannot be empty");
			for (int i = 0; i < path.length; i++)
				if (path[i].startsWith("/"))
					path[i] = path[i].substring(1);

			this.path = path;
		}

		/**
		 * Get the uppermost element of the path.
		 * 
		 * @return The first path element
		 */
		public String getUpperPath() {
			return path[0];
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
	}

	private static final long serialVersionUID = 891843709925731616L;

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

	/**
	 * Zip entries specified by the user.
	 */
	private Map<String, Object> content;

	/**
	 * An optional zip file that acts like the base of the ZipSet.
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
	 * Add an entry to the content map.
	 * 
	 * @param entry The absolute entry to add
	 * @param o     The associated object
	 * @return {@code this}
	 */
	private ZipSet addEntry(EntryPath entry, Object o) {
		if (entry.isNested()) {
			if (!content.containsKey(entry.getUpperPath()))
				content.put(entry.getUpperPath(), new ZipSet());
			if (content.get(entry.getUpperPath()) instanceof Path)
				content.put(entry.getUpperPath(), new ZipSet((Path) content.get(entry.getUpperPath())));
			((ZipSet) content.get(entry.getUpperPath())).addEntry(entry.toLowerPath(), o);
		} else {
			content.put(entry.getUpperPath(), o);
		}

		return this;
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
	 * Write the final zip to the given {@link OutputStream}. This method does not
	 * mutate the ZipSet, so successive calls should produce the same results. This
	 * method does not close the {@link OutputStream} so that it can be called
	 * recursively.
	 * 
	 * @param out The stream that will receive the zip
	 * @throws IOException
	 */
	public void build(OutputStream out) throws IOException {
		var zipOut = new ZipOutputStream(out);

		try (var zipIn = new ZipInputStream(
				// Use either the source file or an empty stream
				source != null ? Files.newInputStream(source) : InputStream.nullInputStream())) {
			build(zipIn, zipOut);

		} finally {
			zipOut.finish();
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
	 * Write the given {@link ZipInputStream} to the given {@link ZipOutputStream}
	 * along with any changes in {@link #content}.
	 * 
	 * @param zipIn  The input zip
	 * @param zipOut The output zip
	 * @throws IOException
	 */
	private void build(ZipInputStream zipIn, ZipOutputStream zipOut) throws IOException {
		Stream<String> toProcess = content.keySet().stream();

		ZipEntry entry;
		while ((entry = zipIn.getNextEntry()) != null) {
			final String name = entry.getName();
			if (!content.containsKey(name)) {
				// There is no corresponding entry in content; just copy the entry exactly
				zipOut.putNextEntry(entry);
				zipIn.transferTo(zipOut);
				zipOut.closeEntry();
			} else if (content.containsKey(name) && content.get(name) instanceof ZipSet) {
				// There is a corresponding ZipSet that should be merged with the entry
				zipOut.putNextEntry(new ZipEntry(name));
				((ZipSet) content.get(name)).build(new ZipInputStream(zipIn), new ZipOutputStream(zipOut));
				zipOut.closeEntry();

				// Don't visit this entry again
				toProcess = toProcess.filter(e -> !e.equals(name));
			} else if (content.containsKey(name) && content.get(name) != null) {
				// Overwrite the source entry
				writeObject(zipOut, name, content.get(name));

				// Don't visit this entry again
				toProcess = toProcess.filter(e -> !e.equals(name));
			} else {
				// The source entry is explicitly excluded
				continue;
			}
		}

		// Copy the rest of the entries
		for (String e : toProcess.collect(Collectors.toList())) {
			writeObject(zipOut, e, content.get(e));
		}
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
	 * Write the given object to the given zip stream.
	 * 
	 * @param zip  The zip to receive the files
	 * @param name The name of the object
	 * @param o    The object to write
	 * @throws IOException
	 */
	private void writeObject(ZipOutputStream zip, String name, Object o) throws IOException {
		if (o instanceof byte[]) {
			if (name.endsWith("/")) {
				zip.putNextEntry(newDirectoryEntry(name));
				zip.closeEntry();
			} else {
				zip.putNextEntry(newFileEntry(name));
				zip.write((byte[]) o);
				zip.closeEntry();
			}
		} else if (o instanceof ZipSet) {
			zip.putNextEntry(newFileEntry(name));
			((ZipSet) o).build(zip);
			zip.closeEntry();
		} else if (o instanceof Path) {
			writePath(zip, name, (Path) o);
		}
	}

	/**
	 * Write the given file or directory to the given zip stream.
	 * 
	 * @param zip  The zip to receive the files
	 * @param name The name of the file or directory
	 * @param path The file or directory to write
	 * @throws IOException
	 */
	private void writePath(ZipOutputStream zip, String name, Path path) throws IOException {
		if (Files.isDirectory(path)) {
			zip.putNextEntry(newDirectoryEntry(name));
			zip.closeEntry();
			for (Path p : Files.list(path).collect(Collectors.toList())) {
				writePath(zip, name + "/" + p.getFileName().toString(), p);
			}
		} else {
			zip.putNextEntry(newFileEntry(name));
			Files.copy(path, zip);
			zip.closeEntry();
		}
	}
}
