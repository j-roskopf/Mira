plugins {
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("MiraDatabase") {
            packageName.set("com.joetr.mira.db")
            generateAsync.set(true)
        }
    }
}
