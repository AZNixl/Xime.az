-- 九键日期提示
-- 在输入特定触发词时，在候选词列表中插入对应日期候选
-- 触发词及对应日期：
--   今天/今日/今 → 当前日期（x月x日）
--   明天/明日/明 → 明天日期（x月x日）
--   昨天/昨日/昨 → 昨天日期（x月x日）
-- 在 engine/filters 增加 - lua_filter@*date_hint

local trigger_map = {
    today     = { "今天", "今日", "今" },
    tomorrow  = { "明天", "明日", "明" },
    yesterday = { "昨天", "昨日", "昨" },
}

-- 构建查找用的 set
local trigger_set = {}
for _, words in pairs(trigger_map) do
    for _, w in ipairs(words) do
        trigger_set[w] = true
    end
end

local function detect_trigger(candidates)
    for _, cand in ipairs(candidates) do
        if trigger_set[cand.text] then
            return cand
        end
    end
    return nil
end

local function date_hint(input, env)
    -- 收集所有候选
    local candidates = {}
    for cand in input:iter() do
        table.insert(candidates, cand)
    end

    if #candidates == 0 then
        return
    end

    -- 查找触发词
    local trigger_cand = detect_trigger(candidates)
    if not trigger_cand then
        -- 无触发词，原样输出
        for _, cand in ipairs(candidates) do
            yield(cand)
        end
        return
    end

    -- 计算偏移天数和日期
    local text = trigger_cand.text
    local offset = 0
    for t, words in pairs(trigger_map) do
        for _, w in ipairs(words) do
            if w == text then
                if t == "tomorrow" then
                    offset = 86400
                elseif t == "yesterday" then
                    offset = -86400
                end
                break
            end
        end
    end

    local target_time = os.time() + offset
    local date_str = os.date("%m月%d日", target_time)

    -- 构建输出：在原触发词后插入日期候选
    for _, cand in ipairs(candidates) do
        yield(cand)
        if cand == trigger_cand then
            local start = cand.start
            local end_ = cand._end
            local date_cand = Candidate("date_hint", start, end_, date_str, "")
            date_cand.quality = cand:get_genuine().quality - 1
            yield(date_cand)
        end
    end
end

return date_hint