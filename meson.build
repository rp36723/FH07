project(
    'senior-design-project',
    'cpp',
    version: '1.0.0',
    default_options: [
        'cpp_std=c++17',
    ],
)

# Prefer a system-provided FlatBuffers, fall back to the subproject.
flatbuffers_dep = dependency('flatbuffers', required: false)
if flatbuffers_dep.found()
    flatc_exe = find_program('flatc', required: true)
else
    flatbuffers_proj = subproject('flatbuffers')
    flatbuffers_dep = flatbuffers_proj.get_variable('flatbuffers_dep')
    flatc_exe = flatbuffers_proj.get_variable('flatc')
endif

subdir('sw/message_formats')
