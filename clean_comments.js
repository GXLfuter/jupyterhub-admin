const fs = require('fs');
const path = require('path');

const BACKEND_DIR = path.join(__dirname, 'backend', 'src', 'main', 'java');

const HEADER = `/*
 * 作者：nailong
 * 时间：2026/6/12
 */
`;

function removeComments(content) {
    let result = '';
    let i = 0;
    const n = content.length;
    let inString = false;
    let stringChar = '';
    let inLineComment = false;
    let inBlockComment = false;

    while (i < n) {
        if (inLineComment) {
            if (content[i] === '\n') {
                inLineComment = false;
                result += content[i];
            }
            i++;
            continue;
        }

        if (inBlockComment) {
            if (i < n - 1 && content[i] === '*' && content[i + 1] === '/') {
                inBlockComment = false;
                i += 2;
            } else {
                i++;
            }
            continue;
        }

        if (inString) {
            result += content[i];
            if (content[i] === '\\' && i + 1 < n) {
                result += content[i + 1];
                i += 2;
                continue;
            }
            if (content[i] === stringChar) {
                inString = false;
            }
            i++;
            continue;
        }

        if (i < n - 1 && content[i] === '/' && content[i + 1] === '/') {
            inLineComment = true;
            i += 2;
            continue;
        }

        if (i < n - 1 && content[i] === '/' && content[i + 1] === '*') {
            inBlockComment = true;
            i += 2;
            continue;
        }

        if (content[i] === '"' || content[i] === "'") {
            inString = true;
            stringChar = content[i];
            result += content[i];
            i++;
            continue;
        }

        result += content[i];
        i++;
    }

    return result;
}

function cleanBlankLines(content) {
    const lines = content.split('\n');
    const cleaned = [];
    let prevBlank = false;
    for (const line of lines) {
        if (line.trim() === '') {
            if (!prevBlank) {
                cleaned.push('');
            }
            prevBlank = true;
        } else {
            cleaned.push(line);
            prevBlank = false;
        }
    }
    return cleaned.join('\n');
}

function processFile(filepath) {
    let content = fs.readFileSync(filepath, 'utf-8');
    const originalSize = content.length;

    content = removeComments(content);
    content = cleanBlankLines(content);

    const pkgIdx = content.indexOf('package');
    if (pkgIdx !== -1) {
        const before = content.substring(0, pkgIdx);
        const after = content.substring(pkgIdx);
        content = before + HEADER + '\n' + after;
    } else {
        content = HEADER + '\n' + content;
    }

    fs.writeFileSync(filepath, content, 'utf-8');
    return { originalSize, newSize: content.length };
}

function walkDir(dir, callback) {
    const files = fs.readdirSync(dir);
    for (const file of files) {
        const filepath = path.join(dir, file);
        const stat = fs.statSync(filepath);
        if (stat.isDirectory()) {
            walkDir(filepath, callback);
        } else if (file.endsWith('.java')) {
            callback(filepath);
        }
    }
}

let count = 0;
let totalOrig = 0;
let totalNew = 0;

walkDir(BACKEND_DIR, (filepath) => {
    const { originalSize, newSize } = processFile(filepath);
    count++;
    totalOrig += originalSize;
    totalNew += newSize;
    const rel = path.relative(BACKEND_DIR, filepath);
    console.log('  处理: ' + rel);
});

console.log('\n完成！共处理 ' + count + ' 个文件');
console.log('原始大小: ' + (totalOrig / 1024).toFixed(1) + ' KB');
console.log('处理后: ' + (totalNew / 1024).toFixed(1) + ' KB');
console.log('减少: ' + ((1 - totalNew / totalOrig) * 100).toFixed(1) + '%');
