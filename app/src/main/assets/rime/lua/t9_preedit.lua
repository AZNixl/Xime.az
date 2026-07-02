-- T9 九宫格预编辑文本转换
-- 将输入框的数字序列转为对应的拼音显示（preedit）
-- 在 engine/filters 增加 - lua_filter@*t9_preedit
--
-- 注意：本 filter 只处理 preedit，不修改 cand.comment。
-- cand.comment 由 Kotlin onRightCandidateSelected 用于计算数字消费位数，
-- 清空 comment 会导致候选词选择后定位错误。
-- 候选注释的显示/隐藏由 UI 层 SettingsPreferences.showCandidateComments 控制。

local function t9_preedit(input, env)
    local config = env.engine.schema.config
    local context = env.engine.context

    local delimiter = config:get_string('speller/delimiter') or " '"
    local auto_delimiter = delimiter:sub(1, 1)
    local manual_delimiter = delimiter:sub(2, 2)

    for cand in input:iter() do
        local genuine_cand = cand:get_genuine()
        local preedit = genuine_cand.preedit or ""
        local comment = genuine_cand.comment or ""

        -- 检测 preedit 中是否包含数字段
        local has_digit = false
        for segment in preedit:gmatch("[^" .. auto_delimiter .. manual_delimiter .. "]+") do
            if segment:match("^%d+$") then
                has_digit = true
                break
            end
        end

        -- 无数字段或无拼音标注，不做处理
        if not has_digit or comment == "" then
            yield(cand)
            goto continue
        end

        -- 拆分 comment 得到拼音段
        local pinyin_parts = {}
        for seg in string.gmatch(comment, "[^%s]+") do
            table.insert(pinyin_parts, seg)
        end

        -- 拆分 preedit 得到输入段
        local input_parts = {}
        local buf = ""
        for char in preedit:gmatch("[%z\1-\127\194-\244][\128-\191]*") do
            if char == auto_delimiter or char == manual_delimiter then
                if #buf > 0 then
                    table.insert(input_parts, buf)
                    buf = ""
                end
                table.insert(input_parts, char)
            else
                buf = buf .. char
            end
        end
        if #buf > 0 then
            table.insert(input_parts, buf)
        end

        -- 替换数字段为拼音
        local pi = 1
        for i, part in ipairs(input_parts) do
            if part == auto_delimiter or part == manual_delimiter then
                input_parts[i] = auto_delimiter
            elseif part:match("^%d+$") then
                local py = pinyin_parts[pi]
                if py then
                    if i == #input_parts and #part == 1 then
                        -- 末尾单数字简拼：只取首字母
                        local prefix = py:sub(1, 2):lower()
                        if prefix == "zh" or prefix == "ch" or prefix == "sh" then
                            input_parts[i] = prefix
                        else
                            input_parts[i] = py:sub(1, 1):lower()
                        end
                    elseif #input_parts == 1 and #pinyin_parts > 1 then
                        -- 单段数字对应多音节：拼接全部拼音
                        local all = {}
                        for j = pi, #pinyin_parts do
                            all[#all+1] = pinyin_parts[j]
                        end
                        input_parts[i] = table.concat(all)
                    else
                        input_parts[i] = py:lower()
                    end
                    pi = pi + 1
                end
            else
                -- 非数字段（已有字母等），消耗一个拼音索引
                pi = pi + 1
            end
        end

        genuine_cand.preedit = table.concat(input_parts)
        yield(cand)
        ::continue::
    end
end

return t9_preedit