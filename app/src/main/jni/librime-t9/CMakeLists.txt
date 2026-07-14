cmake_minimum_required(VERSION 3.18.0)

aux_source_directory(src RIME_T9_SRC)

add_library(rime-t9-objs OBJECT ${RIME_T9_SRC})
if(BUILD_SHARED_LIBS)
  set_target_properties(rime-t9-objs PROPERTIES POSITION_INDEPENDENT_CODE ON)
endif()

set(plugin_name "rime-t9" PARENT_SCOPE)
set(plugin_objs $<TARGET_OBJECTS:rime-t9-objs> PARENT_SCOPE)
set(plugin_deps ${rime_library} PARENT_SCOPE)
set(plugin_modules "t9" PARENT_SCOPE)
