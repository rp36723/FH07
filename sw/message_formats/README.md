# Message Formats

Defines the basic messages used to talk over the bluetooth link.

## Requirements

- Flatbuffers (flatc)

## Building

To build the library, use the flatc command tool. If I get around to it I may switch this to bazel/cmake/standardized makefile.

```Bash
flatc -c --gen-all device_link.fbs 
```
