const heic2any = require("heic2any").default;

function isHeic(bytes) {
    if (bytes.length < 12) return false;
    if (bytes[4] !== 0x66 || bytes[5] !== 0x74 || bytes[6] !== 0x79 || bytes[7] !== 0x70) return false;
    const brand = String.fromCharCode(...bytes.slice(8, Math.min(bytes.length, 32)));
    return /heic|mif1/i.test(brand);
}

function bytesFromBase64(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}

function base64FromBytes(bytes) {
    let binary = "";
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
        binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
}

async function resizeImageBytes(bytes, maxLongEdge, quality) {
    let inputBytes = bytes;
    if (isHeic(bytes)) {
        const converted = await heic2any({
            blob: new Blob([bytes], { type: "image/heic" }),
            toType: "image/jpeg",
            quality: Math.max(0.01, quality / 100),
        });
        const blob = Array.isArray(converted) ? converted[0] : converted;
        inputBytes = new Uint8Array(await blob.arrayBuffer());
    }

    const blob = new Blob([inputBytes]);
    const bitmap = await createImageBitmap(blob);
    const longEdge = Math.max(bitmap.width, bitmap.height);
    const scale = Math.min(1, maxLongEdge / longEdge);
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    canvas.getContext("2d").drawImage(bitmap, 0, 0, width, height);
    if (typeof bitmap.close === "function") bitmap.close();

    const resultBlob = await new Promise((resolve, reject) => {
        canvas.toBlob(
            (result) => (result ? resolve(result) : reject(new Error("canvas.toBlob failed"))),
            "image/jpeg",
            Math.max(0.01, quality / 100),
        );
    });
    return new Uint8Array(await resultBlob.arrayBuffer());
}

async function resizeImageBase64(base64, maxLongEdge, quality) {
    const bytes = bytesFromBase64(base64);
    const result = await resizeImageBytes(bytes, maxLongEdge, quality);
    return base64FromBytes(result);
}

globalThis.__miraResizeImage = resizeImageBase64;
