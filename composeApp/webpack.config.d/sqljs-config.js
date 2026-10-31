const path = require("path");

config.resolve = config.resolve || {};
config.resolve.fallback = {
    ...(config.resolve.fallback || {}),
    crypto: false,
    fs: false,
    path: false,
};
config.resolve.modules = [
    ...(config.resolve.modules || ["node_modules"]),
    path.resolve(__dirname, "../../node_modules"),
];
config.resolve.alias = {
    ...(config.resolve.alias || {}),
    "mira-persistent-sqljs.worker.js": path.resolve(
        __dirname,
        "../../../../composeApp/webpack/mira-persistent-sqljs.worker.js",
    ),
};

config.devServer = config.devServer || {};
config.devServer.historyApiFallback = true;

const imageUtilsPath = path.resolve(__dirname, "../../../../composeApp/webpack/web-image-utils.js");
if (Array.isArray(config.entry.main)) {
    config.entry.main.unshift(imageUtilsPath);
}

const CopyWebpackPlugin = require("copy-webpack-plugin");
config.plugins = config.plugins || [];
config.plugins.push(
    new CopyWebpackPlugin({
        patterns: [
            { from: "../../node_modules/sql.js/dist/sql-wasm.js", to: "sql-wasm.js" },
            { from: "../../node_modules/sql.js/dist/sql-wasm.wasm", to: "sql-wasm.wasm" },
            {
                from: "../../../../composeApp/webpack/mira-persistent-sqljs.worker.js",
                to: "mira-persistent-sqljs.worker.js",
            },
        ],
    }),
);
