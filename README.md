[![Maven central](https://maven-badges.herokuapp.com/maven-central/com.github.cilki/zipset/badge.svg?)](https://search.maven.org/search?q=com.github.cilki)
[![Build Status](https://travis-ci.org/cilki/ZipSet.svg?branch=master)](https://travis-ci.org/cilki/ZipSet)
[![codecov](https://codecov.io/gh/cilki/ZipSet/branch/master/graph/badge.svg)](https://codecov.io/gh/cilki/ZipSet)

**ZipSet** is a small zero-dependency Java library for building zip files lazily. It lets you think of a zip file like a `Set` that can be manipulated with standard set operations. ZipSet doesn't do any I/O until `build` is invoked, so all modifications are applied in a single step without the need for a temporary directory. Even the beautiful `jdk.zipfs` module requires changes to nested zip files be made via a temporary file. 

#### Primary Usage
```
// Create a new ZipSet from an existing zip file
ZipSet zip = new ZipSet(sourceZipFile);

// Lazily add some entries
zip.add("test.txt", "1234".getBytes());     // A byte array
zip.add("test.png", Paths.get("test.png")); // A file on the filesystem
zip.add("inner.zip", anotherZipSet);        // Another ZipSet()

// Lazily add an entry to a nested zip
zip.add("inner.zip!/123.txt", Paths.get("123.txt"))

// Exclude some entries
zip.sub("delete.txt")
zip.sub("inner.zip!/example.txt");

// Output a new zip file containing all modifications in a single sweep
zip.build(outputZipFile);
```

