package com.almus.appcreatorpro.domain.template

import com.almus.appcreatorpro.domain.model.ProjectType

/**
 * Starter files for new projects. These are plain text templates written to the project folder.
 * They have NOT been verified to build (building is a later phase); they are a reasonable starting point.
 */
object ProjectTemplates {

    fun filesFor(projectName: String, packageName: String, type: ProjectType): Map<String, String> =
        when (type) {
            ProjectType.ANDROID_KOTLIN -> androidFiles(projectName, packageName, kotlin = true)
            ProjectType.ANDROID_JAVA -> androidFiles(projectName, packageName, kotlin = false)
            ProjectType.WEB -> webFiles(projectName)
        }

    /** The file opened first when the editor starts. */
    fun defaultOpenFile(type: ProjectType, packageName: String): String = when (type) {
        ProjectType.ANDROID_KOTLIN -> "${sourceDir(packageName)}/MainActivity.kt"
        ProjectType.ANDROID_JAVA -> "${sourceDir(packageName)}/MainActivity.java"
        ProjectType.WEB -> "index.html"
    }

    private fun sourceDir(packageName: String) = "app/src/main/java/${packageName.replace('.', '/')}"

    private fun androidFiles(name: String, pkg: String, kotlin: Boolean): Map<String, String> {
        val files = linkedMapOf<String, String>()
        files["README.md"] = readme(name)
        files["settings.gradle.kts"] = """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }

            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }

            rootProject.name = "${kotlinString(name)}"
            include(":app")
        """.trimIndent() + "\n"
        files["build.gradle.kts"] = (if (kotlin) {
            """
            plugins {
                id("com.android.application") version "8.7.3" apply false
                id("org.jetbrains.kotlin.android") version "2.0.21" apply false
            }
            """
        } else {
            """
            plugins {
                id("com.android.application") version "8.7.3" apply false
            }
            """
        }).trimIndent() + "\n"
        files["gradle.properties"] = "org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\n"
        files["app/build.gradle.kts"] = appGradle(pkg, kotlin)
        files["app/src/main/AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:allowBackup="false"
                    android:label="@string/app_name"
                    android:theme="@android:style/Theme.DeviceDefault.Light">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"
        files["app/src/main/res/values/strings.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">${xmlEscape(name)}</string>
            </resources>
        """.trimIndent() + "\n"
        if (kotlin) {
            files["${sourceDir(pkg)}/MainActivity.kt"] = """
                package $pkg

                import android.app.Activity
                import android.os.Bundle
                import android.widget.TextView

                class MainActivity : Activity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        val view = TextView(this)
                        view.text = "Hello from ${kotlinString(name)}"
                        view.textSize = 24f
                        view.setPadding(48, 96, 48, 48)
                        setContentView(view)
                    }
                }
            """.trimIndent() + "\n"
        } else {
            files["${sourceDir(pkg)}/MainActivity.java"] = """
                package $pkg;

                import android.app.Activity;
                import android.os.Bundle;
                import android.widget.TextView;

                public class MainActivity extends Activity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        TextView view = new TextView(this);
                        view.setText("Hello from ${javaString(name)}");
                        view.setTextSize(24f);
                        view.setPadding(48, 96, 48, 48);
                        setContentView(view);
                    }
                }
            """.trimIndent() + "\n"
        }
        return files
    }

    private fun appGradle(pkg: String, kotlin: Boolean): String {
        val header = if (kotlin) {
            "import org.jetbrains.kotlin.gradle.dsl.JvmTarget\n\nplugins {\n    id(\"com.android.application\")\n    id(\"org.jetbrains.kotlin.android\")\n}\n"
        } else {
            "plugins {\n    id(\"com.android.application\")\n}\n"
        }
        val body = """

            android {
                namespace = "$pkg"
                compileSdk = 35

                defaultConfig {
                    applicationId = "$pkg"
                    minSdk = 26
                    targetSdk = 35
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }
        """.trimIndent()
        val footer = if (kotlin) {
            "\n\nkotlin {\n    compilerOptions {\n        jvmTarget.set(JvmTarget.JVM_17)\n    }\n}\n"
        } else {
            "\n"
        }
        return header + body + footer
    }

    private fun webFiles(name: String): Map<String, String> = linkedMapOf(
        "README.md" to readme(name),
        "index.html" to """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>${xmlEscape(name)}</title>
                <link rel="stylesheet" href="style.css">
            </head>
            <body>
                <main>
                    <h1>${xmlEscape(name)}</h1>
                    <p id="message">Hello, web!</p>
                    <button id="button">Click me</button>
                </main>
                <script src="script.js"></script>
            </body>
            </html>
        """.trimIndent() + "\n",
        "style.css" to """
            body {
                font-family: sans-serif;
                margin: 0;
                padding: 24px;
                background: #f7f8fc;
                color: #1a1b24;
            }

            button {
                padding: 10px 18px;
                border: 0;
                border-radius: 8px;
                background: #3b3fd9;
                color: #fff;
                font-size: 16px;
            }
        """.trimIndent() + "\n",
        "script.js" to """
            const button = document.getElementById("button");
            const message = document.getElementById("message");

            button.addEventListener("click", () => {
                message.textContent = "You clicked the button!";
            });
        """.trimIndent() + "\n",
    )

    private fun readme(name: String) =
        "# $name\n\nCreated with Almus App Creator Pro.\n"

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun kotlinString(s: String) = s
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    private fun javaString(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
