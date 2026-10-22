# R8 rules for the release build. Each rule names the mechanism that needs it, so the
# file stays auditable: nothing is kept without a stated owner, and -dontwarn entries
# are only added for a warning that a real shrink run produced.

# Readable crash stacks. The release pipeline drops source file and line information by
# default, which turns every stack trace from a user's device into "Unknown Source".
# The file name is still replaced with the literal "SourceFile", so no path leaks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson reads the generic field type through the Signature attribute. Without it the
# declared List<T> inside the persisted documents is seen as a raw List, Gson fills it
# with LinkedTreeMap elements, and the cast to the record type fails at read time - the
# stores catch that failure and start with an empty store. The AGP default rules and
# gson's META-INF/proguard/gson.pro already keep the attribute; it is repeated here so the
# data-loss requirement does not depend on either file staying as it is.
-keepattributes Signature

# Gson persisted schemas: the field names are the JSON keys.
# AuthorizationStore, TlsPinStore and FavoriteStore serialise these DTOs with Gson and
# treat every parse failure as "empty store". Obfuscating a field name would therefore
# silently discard persisted trust pins, authorization grants and favorites after an
# upgrade, so both the class names and the field names must survive unchanged.
-keep class com.obby.android.localscreenshare.authorization.store.AuthorizationStore$Document { <fields>; }
-keep class com.obby.android.localscreenshare.authorization.store.AuthorizationStore$EntryRecord { <fields>; }
-keep class com.obby.android.localscreenshare.trust.TlsPinStore$Document { <fields>; }
-keep class com.obby.android.localscreenshare.trust.TlsPinStore$PinRecord { <fields>; }
-keep class com.obby.android.localscreenshare.discovery.favorite.FavoriteStore$FavoriteRecord { <fields>; }

# protobuf-javalite: generated messages extend GeneratedMessageLite, and the lite
# runtime resolves their fields by reflection for parsing and repeated-field views.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# gRPC providers are discovered by ServiceLoader from META-INF/services, so the
# implementation class names and their no-argument constructors must survive.
-keep class io.grpc.okhttp.OkHttpChannelProvider { *; }
-keep class io.grpc.okhttp.OkHttpServerProvider { *; }
-keep class io.grpc.internal.DnsNameResolverProvider { *; }
-keep class io.grpc.internal.PickFirstLoadBalancerProvider { *; }
