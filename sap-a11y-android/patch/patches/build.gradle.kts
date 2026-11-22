group = "app.sapa11y"

patches {
    about {
        name = "Super Auto Pets Accessibility"
        description = "Screen reader support for Super Auto Pets on Android"
        source = "https://github.com/GreenBeanGravy/SuperAutoAccessibility"
        author = "SuperAutoAccessibility Android port"
        contact = "na"
        website = "na"
        // The upstream mod has no license file at its root; check with its author
        // before redistributing the ported dictionary or behaviour.
        license = "See upstream repository"
    }
}

// The native libraries, dictionary and compiled extension (.mpe) that the
// patch injects are produced by ../tools/build_extension.sh and
// ../native/build.sh into ../artifacts, and packaged into the .mpp from here.
sourceSets {
    main {
        resources.srcDir(rootProject.file("../artifacts"))
    }
}
