# Add every library directory that contains its own CMakeLists.txt
file(GLOB children RELATIVE ${CMAKE_CURRENT_SOURCE_DIR} */CMakeLists.txt)

foreach(child ${children})
  get_filename_component(dir ${child} DIRECTORY)
  add_subdirectory(${dir})
endforeach()
