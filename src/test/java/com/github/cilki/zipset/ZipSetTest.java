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

import static java.nio.file.Files.readAllBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeroturnaround.zip.ZipUtil;

class ZipSetTest {

	@Test
	@DisplayName("Build an empty zip")
	void test1() throws IOException {
		assertArrayEquals(readAllBytes(getReference("test1.zip")), new ZipSet().build());
	}

	@Test
	@DisplayName("Build a simple zip with one entry")
	void test2(@TempDir Path temp) throws IOException {
		byte[] bin = Base64.getDecoder().decode(
				"YBObxESMV0L4tQe7jcQirwev0JLWu6Heg6HRX2R3F7XB4512e5nfiuf570oAJnVbzudlp6yWm6XZkjVXWOVHfoNej8oWQCkZdKKJi2K0U45r0UE48+0x0e3IihYYTeNGbzyKkbKY1hcW");
		Files.write(temp.resolve("random.bin"), bin);

		assertZipEquals(temp, getReference("test2.zip"), new ZipSet().add("random.bin", bin).build());
		assertZipEquals(temp, getReference("test2.zip"),
				new ZipSet().add("random.bin", temp.resolve("random.bin")).build());
	}

	@Test
	@DisplayName("Build a simple zip with only directories")
	void test3(@TempDir Path temp) throws IOException {
		Files.createDirectories(temp.resolve("empty1/empty2/empty3"));
		Files.createDirectories(temp.resolve("empty4"));

		assertZipEquals(temp, getReference("test3.zip"),
				new ZipSet().add("empty1/", temp.resolve("empty1")).add("empty4/", temp.resolve("empty4")).build());
	}

	@Test
	@DisplayName("Build a simple zip with many files and directories")
	void test4(@TempDir Path temp) throws IOException {

		assertZipEquals(temp, getReference("test4.zip"), new ZipSet().add(
				"test/1/2/3/5/34/5/6/3/3/7/9/4/2/7/9/5/3/4/3/7/9/2/3/4/6/8/4/3/6/8/6/3/5/2/23/2/3/4/7/9/54/3/2/5/79/6/4/2/2/5/7/9/67/45/2/4/7/98/65/3/3/6/8/65/4/test.bin",
				Base64.getDecoder().decode(
						"Vk8sraFfg39FH8HUmjBf8yCgVyln2wTxrJY720AH97x4TYrBfC2SbFrBBHU0q3f5OcYfR7cNHoCK5e1LEHJrM0dz6VoT7mDfWxZVP1lRyKaHqOIiFdqza8Iy531NQHKj5H6eSLylhYuX6GXiR2e9Mlw4Iw=="))
				.add("test/1/2/3/5/34/5/6/3/3/7/9/4/2/7/9/5/3/4/3/7/9/2/3/4/6/8/4/3/6/8/6/3/5/2/23/2/3/4/7/9/54/3/2/5/79/6/4/2/2/5/7/9/67/45/2/4/7/98/65/3/3/6/8/65/4/test3.bin",
						Base64.getDecoder().decode(
								"eNZbi3QkxI8rFhvukU8PDK8zeJssU11HIr4yyGGe7UwVJvWJ64pL1FuWWnhIuMKTMTqe0QdtM7lfOjw="))
				.add("test/1/2/3/5/34/5/6/8/3/2/3/4/6/8/4/2/3/4/6/8/9/4/3/5/7/9/7/2/3/4/6/4/3/4/5/7/8/4/test2.bin",
						Base64.getDecoder().decode(
								"ecM2TH5g6mw7UuPY6KqKVhzPDYjv43kG/HLgcapP2IlO3Rn0lqFTAtuZIimqJSno4JtULhRR4PDrDpWS18feLXTuvHtoOSQVoUeKl0msMrMuTzegkhxbiqQCwKo77k95DnyJEFLyoZwhGtdH9apolXHG"))
				.add("test2/test4.bin", Base64.getDecoder().decode("rGbAppQtSg=="))
				.add("test/1/2/3/5/34/5/6/8/3/2/3/4/6/8/4/2/3/4/6/8/9/4/3/5/7/9/5/4/3/6/8/6/3/3/5/4/45/34/345/878/45/577/9")
				.add("test2/test4.1.bin", Base64.getDecoder().decode("rGbAppQtSg==")).build());
	}

	@Test
	@DisplayName("Build a nested zip with one entry")
	void test5(@TempDir Path temp) throws IOException {
		Files.createDirectories(temp.resolve("empty1/empty2/empty3"));
		Files.createDirectories(temp.resolve("empty4"));

		assertZipEquals(temp, getReference("test5.zip"),
				new ZipSet().add("test2.zip", getReference("test2.zip")).build());
	}

	/**
	 * Assert that two zip files are equal in content, but not necessarily in file
	 * timestamps or entry order.
	 * 
	 * @param temp      A temporary directory for the operation
	 * @param reference The expected zip file
	 * @param actual    The actual zip file
	 * @throws IOException
	 */
	private static void assertZipEquals(Path temp, Path reference, byte[] actual) throws IOException {
		Path contents1 = Files.createTempDirectory(temp, "1");
		Path contents2 = Files.createTempDirectory(temp, "2");

		ZipUtil.unpack(reference.toFile(), contents1.toFile());
		try (var in = new ByteArrayInputStream(actual)) {
			ZipUtil.unpack(in, contents2.toFile());
		}

		// Check that every file in the reference has an equal counterpart in the actual
		for (Path path : Files.walk(contents1).collect(Collectors.toList())) {
			Path counterpart = contents2.resolve(contents1.relativize(path));
			assertTrue(Files.exists(counterpart), "File not found: " + counterpart);
			assertEquals(Files.isDirectory(path), Files.isDirectory(counterpart));

			if (!Files.isDirectory(path)) {
				if (isZipFile(path)) {
					assertZipEquals(temp, path, readAllBytes(counterpart));
				} else {
					assertArrayEquals(readAllBytes(path), readAllBytes(counterpart));
				}
			}
		}

		// Check that there are no files in the actual not in the reference
		assertEquals(Files.walk(contents1).map(path -> contents1.relativize(path)).collect(Collectors.toSet()),
				Files.walk(contents2).map(path -> contents2.relativize(path)).collect(Collectors.toSet()));

	}

	/**
	 * Check whether the file is a zip file.
	 * 
	 * @param file The file to test
	 * @return Whether the file is a valid zip
	 * @throws IOException If there was an IO error while reading the file
	 */
	private static boolean isZipFile(Path file) throws IOException {
		try (var in = new ZipFile(file.toFile())) {
			return true;
		} catch (ZipException e) {
			return false;
		}
	}

	/**
	 * Get a reference file from the resources directory.
	 * 
	 * @param name The name of the reference file
	 * @return The file path
	 */
	private static Path getReference(String name) {
		return Paths.get("src/test/resources", name);
	}
}
