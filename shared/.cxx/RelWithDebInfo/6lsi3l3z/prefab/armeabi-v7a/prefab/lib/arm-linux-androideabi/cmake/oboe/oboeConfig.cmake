if(NOT TARGET oboe::oboe)
add_library(oboe::oboe SHARED IMPORTED)
set_target_properties(oboe::oboe PROPERTIES
    IMPORTED_LOCATION "/root/.gradle/caches/8.14.3/transforms/7409cd488590cb8e9cd41a6350a034e0/transformed/oboe-1.9.3/prefab/modules/oboe/libs/android.armeabi-v7a/liboboe.so"
    INTERFACE_INCLUDE_DIRECTORIES "/root/.gradle/caches/8.14.3/transforms/7409cd488590cb8e9cd41a6350a034e0/transformed/oboe-1.9.3/prefab/modules/oboe/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

