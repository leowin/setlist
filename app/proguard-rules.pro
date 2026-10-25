# kotlinx.serialization: keep generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class at.zentriert.setlist.** {
    *** Companion;
}
-keepclasseswithmembers class at.zentriert.setlist.** {
    kotlinx.serialization.KSerializer serializer(...);
}
