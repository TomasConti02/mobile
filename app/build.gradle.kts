/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

android {
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    // --- AGGIUNGI QUESTO BLOCCO QUI SOTTO PER IGNORARE I DUPLICATI ---
    jniLibs {
      pickFirsts += "**/libc++_shared.so"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  kotlinOptions {
    jvmTarget = "18"
  }
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 35

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
    minSdk = 31
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  kotlinOptions { jvmTarget = "1.8" }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

dependencies {
  implementation("org.tensorflow:tensorflow-lite:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
  implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0") // AGGIUNGI QUESTA
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
 // implementation("com.google.ai.edge.litert:litert:2.1.0")
  // Support library per ImageProcessor e TensorImage
  implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
  implementation(project(":sdk"))
  implementation("org.yaml:snakeyaml:2.2")
  // Usa un unico Group ID (org.tensorflow) per coerenza
  //implementation("org.tensorflow:tensorflow-lite:2.14.0") ///ADDED
  //implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0") //ADDED
  //implementation("org.tensorflow:tensorflow-lite-support:0.4.4") ///ADDEDE
  ///implementation("org.tensorflow:tensorflow-lite-api:2.14.0") //ADDED
  /*
  * implementation("org.tensorflow:tensorflow-lite:2.16.1")
implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
* */
  //implementation("com.quickbirdstudios:opencv-contrib:4.5.3.0") ///ADDEDE

// RIMUOVI QUESTA RIGA (è un duplicato della precedente con il vecchio ID):
// implementation("org.tensorflow.lite:tensorflow-lite-support:0.4.4")

  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)

  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.mockdevice)

  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
}