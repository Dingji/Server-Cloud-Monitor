#!/usr/bin/env python
"""Query latest available versions of the KMP toolchain from Maven repos."""
import re, urllib.request, json

def meta(base, group, artifact):
    url = f"{base}/{group.replace('.','/')}/{artifact}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            x = r.read().decode("utf-8", "replace")
    except Exception as e:
        return f"ERR {e}"
    vs = re.findall(r"<version>([^<]+)</version>", x)
    rel = re.search(r"<release>([^<]+)</release>", x)
    stable = [v for v in vs if not re.search(r"(alpha|beta|rc|dev|-M|eap|SNAPSHOT)", v, re.I)]
    return {"release": rel.group(1) if rel else None, "last5_stable": stable[-5:], "last3_any": vs[-3:]}

MC = "https://repo1.maven.org/maven2"
GM = "https://dl.google.com/dl/android/maven2"

targets = [
    (MC, "org.jetbrains.kotlin", "kotlin-gradle-plugin"),
    (GM, "com.android.tools.build", "gradle"),
    (MC, "org.jetbrains.compose", "compose-gradle-plugin"),
    (MC, "org.jetbrains.kotlin", "compose-compiler-gradle-plugin"),
    (MC, "io.ktor", "ktor-client-core"),
    (MC, "org.jetbrains.kotlinx", "kotlinx-serialization-json"),
    (MC, "org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
    (MC, "org.jetbrains.kotlinx", "kotlinx-datetime"),
    (MC, "org.jetbrains.androidx.navigation", "navigation-compose"),
    (MC, "org.jetbrains.androidx.lifecycle", "lifecycle-viewmodel-compose"),
    (MC, "com.russhwolf", "multiplatform-settings"),
    (MC, "org.jetbrains.compose.material3", "material3"),
    (MC, "io.ktor", "ktor-client-okhttp"),
    (MC, "io.ktor", "ktor-client-cio"),
    (MC, "io.ktor", "ktor-client-websockets"),
]
for base, g, a in targets:
    print(f"{g}:{a}  ->  {json.dumps(meta(base,g,a), ensure_ascii=False)}", flush=True)

# gradle distributions
try:
    with urllib.request.urlopen("https://services.gradle.org/versions/current", timeout=30) as r:
        print("gradle current:", json.dumps(json.loads(r.read()), indent=1))
except Exception as e:
    print("gradle ERR", e)
