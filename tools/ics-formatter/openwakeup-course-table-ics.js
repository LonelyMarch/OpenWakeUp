(function openWakeUpCourseTableIcsFormatter() {
    'use strict';

    /**
     * OpenWakeUp 独立 ICS 生成器。
     *
     * 本文件由浏览器书签脚本动态加载，不属于 Android/Gradle 源集。实现不依赖 jQuery、
     * FileSaver.js 或教务系统私有接口；研究生课表通过真实 DOM 行数和 rowspan 构建逻辑网格，
     * 本科生旧 EAMS 页面则优先读取其 table0.activities 数据模型。
     */

    const TOOL_NAME = 'OpenWakeUp ICS Formatter';
    const TOOL_VERSION = '1.0.0';
    const TIME_ZONE = 'Asia/Shanghai';
    const MAX_SUPPORTED_WEEK = 60;

    /**
     * 默认的 13 节作息时间。对于实际只有 12 行的研究生课表，按真实行数截取前 12 项，
     * 不使用固定行数重解释整个星期，避免不同日的节次发生偏移。
     */
    const CLASS_TIMES = Object.freeze([
        Object.freeze({ start: '08:15', end: '09:00' }),
        Object.freeze({ start: '09:10', end: '09:55' }),
        Object.freeze({ start: '10:15', end: '11:00' }),
        Object.freeze({ start: '11:10', end: '11:55' }),
        Object.freeze({ start: '13:00', end: '13:45' }),
        Object.freeze({ start: '13:55', end: '14:40' }),
        Object.freeze({ start: '15:00', end: '15:45' }),
        Object.freeze({ start: '15:55', end: '16:40' }),
        Object.freeze({ start: '16:50', end: '17:35' }),
        Object.freeze({ start: '18:00', end: '18:45' }),
        Object.freeze({ start: '18:55', end: '19:40' }),
        Object.freeze({ start: '19:50', end: '20:35' }),
        Object.freeze({ start: '20:45', end: '21:30' }),
    ]);

    /** 中文星期标题到 ISO 星期序号的映射。 */
    const WEEKDAY_BY_TEXT = Object.freeze({
        '星期一': 1,
        '星期二': 2,
        '星期三': 3,
        '星期四': 4,
        '星期五': 5,
        '星期六': 6,
        '星期日': 7,
        '星期天': 7,
        '周一': 1,
        '周二': 2,
        '周三': 3,
        '周四': 4,
        '周五': 5,
        '周六': 6,
        '周日': 7,
        '周天': 7,
    });

    /** 防止用户连续点击书签后同时弹出多组输入框并重复下载。 */
    if (window.__openWakeUpCourseTableIcsFormatterRunning) {
        window.alert(`${TOOL_NAME} 已经在运行。`);
        return;
    }
    window.__openWakeUpCourseTableIcsFormatterRunning = true;

    /**
     * 主流程：发现课表数据、获取学期参数、构建 iCalendar 文本并下载。
     */
    function main() {
        const contexts = collectAccessibleContexts(window);
        const extracted = extractCourseTable(contexts);
        if (!extracted || extracted.entries.length === 0) {
            throw new Error('没有找到可导出的课表。请先进入“我的课表/查看课表”页面并等待课表加载完成。');
        }
        if (extracted.nodeCount > CLASS_TIMES.length) {
            throw new Error(`检测到每天 ${extracted.nodeCount} 节，但工具只配置了 ${CLASS_TIMES.length} 节作息。`);
        }

        const suggestedMonday = nearestMonday(new Date());
        const firstMondayText = window.prompt(
            '请输入本学期第一周周一（YYYY-MM-DD）：',
            formatDate(suggestedMonday),
        );
        if (firstMondayText === null) return;
        const firstMonday = parseDate(firstMondayText);
        if (!firstMonday || firstMonday.getUTCDay() !== 1) {
            throw new Error('日期格式无效，或所选日期不是周一。请使用 YYYY-MM-DD。');
        }

        const explicitMaxWeek = extracted.entries.flatMap((entry) => entry.weeks);
        const detectedMaxWeek = explicitMaxWeek.length > 0 ? Math.max(...explicitMaxWeek) : 18;
        const maxWeekText = window.prompt(
            '请输入本学期总周数（仅用于没有标注周次的课程）：',
            String(detectedMaxWeek),
        );
        if (maxWeekText === null) return;
        const maxWeek = Number.parseInt(maxWeekText, 10);
        if (!Number.isInteger(maxWeek) || maxWeek < 1 || maxWeek > MAX_SUPPORTED_WEEK) {
            throw new Error(`学期周数必须是 1～${MAX_SUPPORTED_WEEK} 的整数。`);
        }

        const normalizedEntries = normalizeEntries(extracted.entries, maxWeek);
        if (normalizedEntries.length === 0) {
            throw new Error('课表单元格已找到，但没有解析出有效的课程名称和周次。');
        }
        const confirmed = window.confirm(
            `已识别 ${normalizedEntries.length} 个课程时间段、每天 ${extracted.nodeCount} 节。\n\n` +
            `课表：${extracted.calendarName}\n第一周周一：${formatDate(firstMonday)}\n\n是否下载 ICS？`,
        );
        if (!confirmed) return;

        const calendarText = buildCalendar({
            calendarName: extracted.calendarName,
            entries: normalizedEntries,
            firstMonday,
            nodeCount: extracted.nodeCount,
        });
        downloadCalendar(calendarText, `${sanitizeFilename(extracted.calendarName)}.ics`);
    }

    /**
     * 收集当前页面以及所有同源 frame/iframe 的 window 与 document。
     *
     * 研究生系统把课表放在 frmright 中；同源情况下可以直接读取，跨域 frame 会被浏览器安全策略跳过。
     */
    function collectAccessibleContexts(rootWindow) {
        const contexts = [];
        const visited = new Set();

        function visit(candidateWindow) {
            if (!candidateWindow || visited.has(candidateWindow)) return;
            visited.add(candidateWindow);
            try {
                const document = candidateWindow.document;
                contexts.push({ window: candidateWindow, document });
                Array.from(document.querySelectorAll('frame, iframe')).forEach((frame) => {
                    try {
                        visit(frame.contentWindow);
                    } catch (ignored) {
                        /* 跨域 frame 由浏览器拦截，当前页面其他上下文仍可继续检测。 */
                    }
                });
            } catch (ignored) {
                /* 无访问权限的 window 不参与课表发现。 */
            }
        }

        visit(rootWindow);
        return contexts;
    }

    /**
     * 优先读取本科 EAMS 的结构化数据；找不到时再解析研究生系统的 HTML 表格。
     */
    function extractCourseTable(contexts) {
        for (const context of contexts) {
            const modelResult = extractFromEamsModel(context.window, context.document);
            if (modelResult && modelResult.entries.length > 0) return modelResult;
        }
        for (const context of contexts) {
            const tableResult = extractFromHtmlTable(context.document);
            if (tableResult && tableResult.entries.length > 0) return tableResult;
        }
        return null;
    }

    /**
     * 从旧 EAMS 页面公开在 window.table0.activities 中的结构化课表提取课程。
     *
     * @param pageWindow 候选页面 window
     * @param document 候选页面 document
     * @return 结构化课表；当前页面不含 EAMS 模型时返回 null
     */
    function extractFromEamsModel(pageWindow, document) {
        const activities = pageWindow.table0 && pageWindow.table0.activities;
        if (!activities || typeof activities.length !== 'number' || activities.length < 7) return null;
        const nodeCount = Math.floor(activities.length / 7);
        if (nodeCount < 1 || nodeCount > CLASS_TIMES.length) return null;

        const grouped = new Map();
        for (let linearIndex = 0; linearIndex < activities.length; linearIndex += 1) {
            const day = Math.floor(linearIndex / nodeCount) + 1;
            const node = linearIndex % nodeCount + 1;
            const slotActivities = Array.from(activities[linearIndex] || []);
            slotActivities.forEach((activity) => {
                const name = stripCourseSerial(activity.courseName);
                if (!name) return;
                const teacher = cleanText(activity.teacherName);
                const room = cleanText(activity.roomName);
                const weeks = weeksFromBooleanArray(activity.vaildWeeks || activity.validWeeks || []);
                const key = [name, teacher, room, day, weeks.join('.')].join('\u0001');
                if (!grouped.has(key)) {
                    grouped.set(key, { name, teacher, room, day, weeks, nodes: new Set() });
                }
                grouped.get(key).nodes.add(node);
            });
        }

        const entries = [];
        grouped.forEach((course) => {
            splitConsecutiveNumbers(Array.from(course.nodes)).forEach((nodes) => {
                entries.push({
                    name: course.name,
                    teacher: course.teacher,
                    room: course.room,
                    day: course.day,
                    startNode: nodes[0],
                    step: nodes.length,
                    weeks: course.weeks,
                });
            });
        });
        return {
            entries,
            nodeCount,
            calendarName: detectCalendarName(document),
        };
    }

    /** 把 EAMS 的 0/1 周次数组转换为从 1 开始的周次集合。 */
    function weeksFromBooleanArray(values) {
        const weeks = [];
        Array.from(values).forEach((enabled, index) => {
            if (Number(enabled) === 1) weeks.push(index);
        });
        return weeks.filter((week) => week >= 1 && week <= MAX_SUPPORTED_WEEK);
    }

    /**
     * 在页面所有 table 中寻找同时包含星期标题和节次行的最佳候选表格。
     */
    function extractFromHtmlTable(document) {
        const candidates = Array.from(document.querySelectorAll('table')).map((table) => ({
            table,
            score: scoreScheduleTable(table),
        })).filter((candidate) => candidate.score > 0).sort((left, right) => right.score - left.score);
        if (candidates.length === 0) return null;

        const table = candidates[0].table;
        const logicalGrid = buildLogicalGrid(table);
        const weekdayColumns = findWeekdayColumns(logicalGrid);
        const firstWeekdayColumn = Math.min(...weekdayColumns.keys());
        const nodeRows = findNodeRows(logicalGrid, firstWeekdayColumn);
        if (weekdayColumns.size < 5 || nodeRows.length === 0) return null;

        const entries = [];
        nodeRows.forEach(({ rowIndex, node }) => {
            weekdayColumns.forEach((day, columnIndex) => {
                const reference = logicalGrid[rowIndex] && logicalGrid[rowIndex][columnIndex];
                if (!reference || reference.originRow !== rowIndex) return;
                const step = nodeRows.filter(({ rowIndex: coveredRow }) => {
                    const covered = logicalGrid[coveredRow] && logicalGrid[coveredRow][columnIndex];
                    return covered && covered.cell === reference.cell;
                }).length;
                if (step < 1) return;
                parseCourseCell(reference.cell).forEach((record) => {
                    entries.push({
                        ...record,
                        day,
                        startNode: node,
                        step,
                    });
                });
            });
        });
        return {
            entries,
            nodeCount: nodeRows.length,
            calendarName: detectCalendarName(document),
        };
    }

    /** 候选表格评分：星期标题与“第 n 节”行越多，越可能是目标课表。 */
    function scoreScheduleTable(table) {
        // 外层排版表可能完整包住真正课表；只处理不再嵌套 table 的最内层候选，避免重复展开。
        if (table.querySelectorAll('table').length > 0) return 0;
        const text = cleanText(table.textContent);
        const weekdayCount = Object.keys(WEEKDAY_BY_TEXT).filter((weekday) => text.includes(weekday)).length;
        const nodeCount = (text.match(/第\s*[一二三四五六七八九十百\d]+\s*节/g) || []).length;
        return weekdayCount >= 5 && nodeCount >= 4 ? weekdayCount * 100 + nodeCount : 0;
    }

    /**
     * 将带 rowspan/colspan 的 HTML table 展开为逻辑网格，每个位置保存原始 cell 及其左上角坐标。
     */
    function buildLogicalGrid(table) {
        const grid = [];
        Array.from(table.rows).forEach((row, rowIndex) => {
            if (!grid[rowIndex]) grid[rowIndex] = [];
            let columnIndex = 0;
            Array.from(row.cells).forEach((cell) => {
                while (grid[rowIndex][columnIndex]) columnIndex += 1;
                const rowSpan = Math.max(1, Number(cell.rowSpan) || 1);
                const columnSpan = Math.max(1, Number(cell.colSpan) || 1);
                for (let rowOffset = 0; rowOffset < rowSpan; rowOffset += 1) {
                    const targetRow = rowIndex + rowOffset;
                    if (!grid[targetRow]) grid[targetRow] = [];
                    for (let columnOffset = 0; columnOffset < columnSpan; columnOffset += 1) {
                        grid[targetRow][columnIndex + columnOffset] = {
                            cell,
                            originRow: rowIndex,
                            originColumn: columnIndex,
                        };
                    }
                }
                columnIndex += columnSpan;
            });
        });
        return grid;
    }

    /** 从逻辑表头中识别星期列，返回“逻辑列号 → ISO 星期”。 */
    function findWeekdayColumns(grid) {
        const result = new Map();
        grid.forEach((row) => {
            row.forEach((reference, columnIndex) => {
                if (!reference) return;
                const text = cleanText(reference.cell.textContent);
                const matched = Object.keys(WEEKDAY_BY_TEXT).find((weekday) => text === weekday || text.includes(weekday));
                if (matched && !result.has(columnIndex)) result.set(columnIndex, WEEKDAY_BY_TEXT[matched]);
            });
        });
        return result;
    }

    /**
     * 按页面真实出现顺序收集节次行。节次编号由行顺序生成，避免中文数字或页面特殊文案造成偏差。
     */
    function findNodeRows(grid, firstWeekdayColumn) {
        const rows = [];
        grid.forEach((row, rowIndex) => {
            const hasNodeLabel = row.some((reference, columnIndex) => {
                // 节次标题必定位于第一个星期列左侧，课程名称中的“第一节课”不会被误判为行标题。
                if (columnIndex >= firstWeekdayColumn) return false;
                if (!reference || reference.originRow !== rowIndex) return false;
                return /第\s*[一二三四五六七八九十百\d]+\s*节/.test(cleanText(reference.cell.textContent));
            });
            if (hasNodeLabel) rows.push({ rowIndex, node: rows.length + 1 });
        });
        return rows;
    }

    /**
     * 解析研究生课表单元格。
     *
     * 页面内部顺序通常为“课程名 周次 教师 教室”，且同一课程可包含多组三元组。
     * 解析时不依赖视觉换行；找不到周次结构时仍保留课程名，稍后按用户输入的总周数处理。
     */
    function parseCourseCell(cell) {
        const tokens = htmlCellTokens(cell);
        if (tokens.length === 0) return [];
        const firstWeekIndex = tokens.findIndex(looksLikeWeekExpression);
        if (firstWeekIndex < 0) {
            const name = stripCourseSerial(tokens.join(' '));
            return name && !/^第.+节$/.test(name) ? [{ name, teacher: '', room: '', weeks: [] }] : [];
        }

        const name = stripCourseSerial(tokens.slice(0, firstWeekIndex).join(' '));
        if (!name) return [];
        const records = [];
        let index = firstWeekIndex;
        while (index < tokens.length) {
            if (!looksLikeWeekExpression(tokens[index])) {
                index += 1;
                continue;
            }
            const weeks = parseWeekExpression(tokens[index]);
            const teacher = cleanText(tokens[index + 1]);
            const room = cleanText(tokens[index + 2]);
            records.push({ name, teacher, room, weeks });
            index += 3;
        }
        return records.length > 0 ? records : [{ name, teacher: '', room: '', weeks: [] }];
    }

    /** 把单元格 HTML 转为稳定的空白分词；脚本、样式和隐藏表单控件不参与课程文本。 */
    function htmlCellTokens(cell) {
        const clone = cell.cloneNode(true);
        clone.querySelectorAll('script, style, input, button, select, option').forEach((element) => element.remove());
        clone.querySelectorAll('br, p, div, li').forEach((element) => {
            element.insertAdjacentText('beforebegin', ' ');
            element.insertAdjacentText('afterend', ' ');
        });
        return cleanText(clone.textContent).split(/\s+/).filter(Boolean);
    }

    /** 判断一个分词是否含“连1-16、单1-3、第1-8周”等周次表达式。 */
    function looksLikeWeekExpression(token) {
        const text = normalizePunctuation(token);
        return /(?:第|连|单|双)\s*\d{1,2}/.test(text) ||
            /^\d{1,2}\s*[-~至]\s*\d{1,2}\s*周?/.test(text) ||
            /^\d{1,2}\s*(?:周)?(?:,|$)/.test(text);
    }

    /**
     * 解析周次表达式，支持连续周、单双周、多段周次以及“除第 n 周”。
     */
    function parseWeekExpression(rawText) {
        const text = normalizePunctuation(rawText);
        const excluded = new Set();
        const exclusionPattern = /除\s*(?:第)?\s*(\d{1,2})(?:\s*[-~至]\s*(\d{1,2}))?\s*周?/g;
        for (const match of text.matchAll(exclusionPattern)) {
            addRange(excluded, Number(match[1]), Number(match[2] || match[1]), 'all');
        }

        const included = new Set();
        const rangePattern = /(第|连|单|双)?\s*(\d{1,2})(?:\s*[-~至]\s*(\d{1,2}))?\s*周?/g;
        for (const match of text.replace(exclusionPattern, ' ').matchAll(rangePattern)) {
            const mode = match[1] === '单' ? 'odd' : match[1] === '双' ? 'even' : 'all';
            addRange(included, Number(match[2]), Number(match[3] || match[2]), mode);
        }
        excluded.forEach((week) => included.delete(week));
        return Array.from(included).sort((left, right) => left - right);
    }

    /** 按连续/单周/双周规则向集合加入闭区间周次。 */
    function addRange(target, rawStart, rawEnd, mode) {
        const start = Math.max(1, Math.min(rawStart, rawEnd));
        const end = Math.min(MAX_SUPPORTED_WEEK, Math.max(rawStart, rawEnd));
        for (let week = start; week <= end; week += 1) {
            if (mode === 'odd' && week % 2 === 0) continue;
            if (mode === 'even' && week % 2 !== 0) continue;
            target.add(week);
        }
    }

    /** 从页面标题、学期输入框或正文中提取课表名称。 */
    function detectCalendarName(document) {
        const inputs = Array.from(document.querySelectorAll('input, select, option'));
        const inputValue = inputs.map((element) => cleanText(element.value || element.textContent))
            .find((value) => /\d{4}\s*[-—]\s*\d{4}.*学期/.test(value));
        if (inputValue) return inputValue;
        const bodyText = cleanText(document.body && document.body.textContent);
        const semester = bodyText.match(/\d{4}\s*[-—]\s*\d{4}\s*学年[^\s]{0,12}学期/);
        return semester ? semester[0] : cleanText(document.title) || 'OpenWakeUp 课程表';
    }

    /**
     * 合并重复时间段、补齐缺省周次，并过滤越界节次。
     */
    function normalizeEntries(entries, maxWeek) {
        const merged = new Map();
        entries.forEach((entry) => {
            // 在最终合并入口再次清理，覆盖结构化模型与 HTML 表格两条数据来源。
            const name = stripCourseSerial(entry.name);
            const day = Number(entry.day);
            const startNode = Number(entry.startNode);
            const step = Number(entry.step);
            if (!name || day < 1 || day > 7 || startNode < 1 || step < 1) return;
            const weeks = entry.weeks.length > 0
                ? entry.weeks.filter((week) => week >= 1 && week <= maxWeek)
                : Array.from({ length: maxWeek }, (_, index) => index + 1);
            const key = [name, cleanText(entry.teacher), cleanText(entry.room), day, startNode, step].join('\u0001');
            if (!merged.has(key)) {
                merged.set(key, {
                    name,
                    teacher: cleanText(entry.teacher),
                    room: cleanText(entry.room),
                    day,
                    startNode,
                    step,
                    weeks: new Set(),
                });
            }
            weeks.forEach((week) => merged.get(key).weeks.add(week));
        });
        return Array.from(merged.values()).map((entry) => ({
            ...entry,
            weeks: Array.from(entry.weeks).sort((left, right) => left - right),
        })).filter((entry) => entry.weeks.length > 0);
    }

    /**
     * 构建 RFC 5545 VCALENDAR。每个规律周次片段使用一条 RRULE，并附加 OpenWakeUp 专用节次字段。
     */
    function buildCalendar({ calendarName, entries, firstMonday, nodeCount }) {
        const lines = [
            'BEGIN:VCALENDAR',
            'VERSION:2.0',
            'CALSCALE:GREGORIAN',
            'METHOD:PUBLISH',
            `PRODID:-//OpenWakeUp//${TOOL_NAME} ${TOOL_VERSION}//CN`,
            `X-WR-CALNAME:${escapeIcsText(calendarName)}`,
            `X-WR-TIMEZONE:${TIME_ZONE}`,
            `X-OPENWAKEUP-NODE-COUNT:${nodeCount}`,
            `X-OPENWAKEUP-SEMESTER-START:${formatDate(firstMonday).replace(/-/g, '')}`,
        ];
        const stamp = formatUtcTimestamp(new Date());
        let eventIndex = 0;

        entries.forEach((entry) => {
            compressWeeks(entry.weeks).forEach((weekRange) => {
                const firstDate = addUtcDays(firstMonday, (weekRange.first - 1) * 7 + entry.day - 1);
                const lastNode = entry.startNode + entry.step - 1;
                const startTime = CLASS_TIMES[entry.startNode - 1];
                const endTime = CLASS_TIMES[lastNode - 1];
                if (!startTime || !endTime) return;
                eventIndex += 1;
                const uidSeed = [calendarName, entry.name, entry.day, entry.startNode, weekRange.first, eventIndex].join('|');
                lines.push(
                    'BEGIN:VEVENT',
                    `UID:${stableHash(uidSeed)}-${weekRange.first}@openwakeup`,
                    `DTSTAMP:${stamp}`,
                    `DTSTART:${formatShanghaiTimeAsUtc(firstDate, startTime.start)}`,
                    `DTEND:${formatShanghaiTimeAsUtc(firstDate, endTime.end)}`,
                );
                if (weekRange.count > 1) {
                    lines.push(`RRULE:FREQ=WEEKLY;COUNT=${weekRange.count};INTERVAL=${weekRange.interval}`);
                }
                lines.push(
                    `SUMMARY:${escapeIcsText(entry.name)}`,
                    `DESCRIPTION:${escapeIcsText([entry.name, entry.room, entry.teacher].filter(Boolean).join(' '))}`,
                    `LOCATION:${escapeIcsText(entry.room)}`,
                    `CATEGORIES:${escapeIcsText(TOOL_NAME)}${entry.teacher ? `,${escapeIcsText(entry.teacher)}` : ''}`,
                    `X-OPENWAKEUP-DAY:${entry.day}`,
                    `X-OPENWAKEUP-START-NODE:${entry.startNode}`,
                    `X-OPENWAKEUP-STEP:${entry.step}`,
                    `X-OPENWAKEUP-WEEKS:${weekRange.weeks.join(',')}`,
                    'BEGIN:VALARM',
                    'ACTION:DISPLAY',
                    'TRIGGER:-PT10M',
                    `DESCRIPTION:${escapeIcsText(entry.name)}`,
                    'END:VALARM',
                    'END:VEVENT',
                );
            });
        });
        lines.push('END:VCALENDAR');
        return lines.flatMap(foldIcsLine).join('\r\n') + '\r\n';
    }

    /**
     * 将周次压缩为固定间隔的片段；连续周得到 INTERVAL=1，单双周得到 INTERVAL=2。
     */
    function compressWeeks(rawWeeks) {
        const remaining = new Set(Array.from(new Set(rawWeeks)).sort((left, right) => left - right));
        const ranges = [];
        while (remaining.size > 0) {
            const sorted = Array.from(remaining).sort((left, right) => left - right);
            const first = sorted[0];
            const interval = remaining.has(first + 1) ? 1 : remaining.has(first + 2) ? 2 : 1;
            const weeks = [first];
            remaining.delete(first);
            let next = first + interval;
            while (remaining.has(next)) {
                weeks.push(next);
                remaining.delete(next);
                next += interval;
            }
            ranges.push({
                first,
                last: weeks[weeks.length - 1],
                interval,
                count: weeks.length,
                weeks,
            });
        }
        return ranges;
    }

    /** 把排序前可能无序的节次拆成多个连续区间。 */
    function splitConsecutiveNumbers(values) {
        const sorted = Array.from(new Set(values)).sort((left, right) => left - right);
        const groups = [];
        sorted.forEach((value) => {
            const current = groups[groups.length - 1];
            if (!current || current[current.length - 1] + 1 !== value) groups.push([value]);
            else current.push(value);
        });
        return groups;
    }

    /** 将上海时区本地日期时间换算为 UTC ICS 时间；上海无夏令时，固定使用 UTC+8。 */
    function formatShanghaiTimeAsUtc(date, timeText) {
        const [hour, minute] = timeText.split(':').map(Number);
        const utc = new Date(Date.UTC(
            date.getUTCFullYear(),
            date.getUTCMonth(),
            date.getUTCDate(),
            hour - 8,
            minute,
            0,
        ));
        return formatUtcTimestamp(utc);
    }

    /** 把 Date 输出为 RFC 5545 基本 UTC 时间格式 yyyyMMdd'T'HHmmss'Z'。 */
    function formatUtcTimestamp(date) {
        return [
            String(date.getUTCFullYear()).padStart(4, '0'),
            String(date.getUTCMonth() + 1).padStart(2, '0'),
            String(date.getUTCDate()).padStart(2, '0'),
            'T',
            String(date.getUTCHours()).padStart(2, '0'),
            String(date.getUTCMinutes()).padStart(2, '0'),
            String(date.getUTCSeconds()).padStart(2, '0'),
            'Z',
        ].join('');
    }

    /** 严格解析 YYYY-MM-DD，并使用 UTC 日期运算避免浏览器夏令时影响周次。 */
    function parseDate(text) {
        const match = String(text || '').trim().match(/^(\d{4})-(\d{2})-(\d{2})$/);
        if (!match) return null;
        const result = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
        return formatDate(result) === match[0] ? result : null;
    }

    /** 返回给定日期所在周的周一，内部结果使用 UTC 零点。 */
    function nearestMonday(date) {
        const utcDate = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()));
        const weekday = utcDate.getUTCDay() || 7;
        return addUtcDays(utcDate, 1 - weekday);
    }

    /** 使用 UTC 日历语义增加天数。 */
    function addUtcDays(date, days) {
        const result = new Date(date.getTime());
        result.setUTCDate(result.getUTCDate() + days);
        return result;
    }

    /** 输出 YYYY-MM-DD 日期。 */
    function formatDate(date) {
        return [
            String(date.getUTCFullYear()).padStart(4, '0'),
            String(date.getUTCMonth() + 1).padStart(2, '0'),
            String(date.getUTCDate()).padStart(2, '0'),
        ].join('-');
    }

    /** RFC 5545 TEXT 转义：反斜杠必须最先处理。 */
    function escapeIcsText(value) {
        return String(value || '')
            .replace(/\\/g, '\\\\')
            .replace(/\r?\n/g, '\\n')
            .replace(/;/g, '\\;')
            .replace(/,/g, '\\,');
    }

    /**
     * 按 RFC 5545 的 75 octets 上限折行；后续行开头的一个空格也计入长度。
     */
    function foldIcsLine(line) {
        const encoder = new TextEncoder();
        const result = [];
        let current = '';
        let byteLimit = 75;
        for (const character of String(line)) {
            const candidate = current + character;
            if (encoder.encode(candidate).length > byteLimit && current.length > 0) {
                result.push(result.length === 0 ? current : ` ${current}`);
                current = character;
                byteLimit = 74;
            } else {
                current = candidate;
            }
        }
        result.push(result.length === 0 ? current : ` ${current}`);
        return result;
    }

    /**
     * 下载无 BOM 的 UTF-8 text/calendar 文件，并立即回收临时 Blob URL。
     *
     * ICS 首行必须直接从 `BEGIN:VCALENDAR` 开始；额外 BOM 会使部分严格解析器无法识别日历头。
     */
    function downloadCalendar(text, filename) {
        const blob = new Blob([text], { type: 'text/calendar;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = filename;
        anchor.style.display = 'none';
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    /** 生成跨刷新稳定、足以作为本地事件 UID 前缀的 32 位散列。 */
    function stableHash(value) {
        let hash = 0x811c9dc5;
        for (const character of String(value)) {
            hash ^= character.codePointAt(0);
            hash = Math.imul(hash, 0x01000193);
        }
        return (hash >>> 0).toString(16).padStart(8, '0');
    }

    /** 清理页面文本中的不换行空格与连续空白。 */
    function cleanText(value) {
        return String(value || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
    }

    /**
     * 移除课程名称末尾的教务课程序号，例如 `(G2700635.01)`、`（M1800220.J4）`。
     *
     * 正则要求括号内容以字母开头、紧跟至少四位数字并包含点号后缀，因而不会误删
     * “随机数学（含概率论、数理统计、随机过程）”这类课程名称本身的说明括号。
     */
    function stripCourseSerial(value) {
        return cleanText(value).replace(/\s*[（(][A-Za-z]\d{4,}(?:\.[A-Za-z0-9]+)+[)）]\s*$/u, '').trim();
    }

    /** 统一全角标点和多种范围连接符，便于周次解析。 */
    function normalizePunctuation(value) {
        return cleanText(value)
            .replace(/[（(]/g, ' ')
            .replace(/[）)]/g, ' ')
            .replace(/[，、；;]/g, ',')
            .replace(/[—–~～至]/g, '-');
    }

    /** 把课表名转换为 Windows、Android 与常见浏览器均可接受的文件名。 */
    function sanitizeFilename(value) {
        const sanitized = cleanText(value).replace(/[\\/:*?"<>|]/g, '_').replace(/[. ]+$/g, '');
        return sanitized || 'OpenWakeUp课程表';
    }

    try {
        main();
    } catch (error) {
        console.error(`[${TOOL_NAME}]`, error);
        window.alert(`${TOOL_NAME}：${error && error.message ? error.message : String(error)}`);
    } finally {
        window.__openWakeUpCourseTableIcsFormatterRunning = false;
    }
}());
