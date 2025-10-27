# Top-level meson.build exposes `flatc_exe` and `flatbuffers_dep`.

schema = files('device_link.fbs')

flatc_gen = generator(
    flatc_exe,
    output: '@BASENAME@_generated.h',
    arguments: [
        '--cpp',
        '--gen-all',
        '-o', meson.current_build_dir(),
        '-I', meson.current_source_dir(),
        '@INPUT@',
    ],
)

generated_headers = flatc_gen.process(schema)

python_mod = import('python')
py3 = python_mod.find_installation('python3')

custom_target(
    'message_formats',
    input: generated_headers,
    output: 'message_formats.stamp',
    command: [py3, '-c', 'open("@OUTPUT@", "w").close()'],
    build_by_default: true,
)

message_formats_inc = include_directories('.')

message_formats_dep = declare_dependency(
    include_directories: message_formats_inc,
    sources: generated_headers,
    dependencies: flatbuffers_dep,
)

meson.override_dependency('message_formats', message_formats_dep)
