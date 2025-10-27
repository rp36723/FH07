# Message Formats

Defines the basic messages used to talk over the bluetooth link.

## Requirements

- Flatbuffers (flatc)

## Building

To build the library, we use meson for both compilation and library inclusion.

Make sure the meson build directory is initialized:

```Bash
meson setup build
```

Then compile with meson:

```Bash
meson compile -C build message_formats
```

## Including

To include this library in other code, ensure the `meson.build` file lists `message_formats` as a dependency. From here it can be used as `#include "device_link_generated"`.
