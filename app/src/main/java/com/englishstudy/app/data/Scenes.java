package com.englishstudy.app.data;

import java.util.ArrayList;
import java.util.List;

/** 口语场景对话素材：8 个高频场景，每个 4-5 轮 NPC 台词 + 应答提示 + 关键词 + 常用句。 */
public final class Scenes {

    public static final class Line {
        public final String npc;
        public final String hint;
        public final String[] keys;

        Line(String npc, String hint, String... keys) {
            this.npc = npc;
            this.hint = hint;
            this.keys = keys;
        }
    }

    public static final class Scene {
        public final String id;
        public final String title;
        public final String level;
        public final String goal;
        public final List<Line> lines = new ArrayList<>();
        public final List<String> phrases = new ArrayList<>();

        Scene(String id, String title, String level, String goal) {
            this.id = id;
            this.title = title;
            this.level = level;
            this.goal = goal;
        }

        Scene line(String npc, String hint, String... keys) {
            lines.add(new Line(npc, hint, keys));
            return this;
        }

        Scene phrase(String... ps) {
            for (String p : ps) phrases.add(p);
            return this;
        }
    }

    private static List<Scene> all;

    public static List<Scene> all() {
        if (all != null) return all;
        List<Scene> l = new ArrayList<>();

        l.add(new Scene("cafe", "点咖啡", "A2", "在咖啡店点单、选择大小、付款")
                .line("Hi there! What can I get for you today?", "点一杯拿铁，说尺寸", "I'd like", "latte", "medium")
                .line("Sure. Would you like that hot or iced?", "回答热的还是冰的", "hot", "iced", "please")
                .line("Great. Anything else? Maybe a pastry?", "可以加一句 no thanks，或再点一份", "no thanks", "that's all", "a muffin")
                .line("That'll be five fifty. Cash or card?", "说刷卡", "card", "credit card", "by card")
                .line("Perfect. Your name for the cup?", "报名字")
                .phrase("I'd like a medium latte, please.", "For here or to go?",
                        "That's all, thanks.", "Can I pay by card?"));

        l.add(new Scene("interview", "英文面试", "B1", "自我介绍、谈经验、问问题")
                .line("Thanks for coming in. Could you tell me a bit about yourself?", "结构：现在做什么 + 一两个亮点", "I'm", "work", "experience", "responsible for")
                .line("What would you say is your greatest strength?", "一个优点 + 一个具体例子", "my strength", "good at", "for example")
                .line("Tell me about a time you handled a difficult situation.", "背景—做法—结果", "last year", "so I", "as a result")
                .line("Why do you want to join our company?", "把公司特点和个人目标连起来", "I'm interested in", "because", "grow")
                .line("Do you have any questions for us?", "至少问一个，显得有诚意", "could you tell me", "what does", "team")
                .phrase("I have three years of experience in…",
                        "I'm good at solving problems under pressure.",
                        "Could you tell me more about the team?"));

        l.add(new Scene("direction", "问路", "A2", "问路并听懂方向")
                .line("You look a bit lost. Can I help you?", "问地铁站怎么走", "excuse me", "how do I get to", "subway")
                .line("Go straight for two blocks, then turn left at the bank.", "用一句话确认你听懂了", "so", "straight", "turn left")
                .line("Yes, exactly. It's on your right, next to a bakery.", "问大概多远", "how far", "walk", "minutes")
                .line("About five minutes on foot.", "道谢", "thank you", "thanks a lot")
                .phrase("Excuse me, how do I get to the subway station?",
                        "Do I turn left at the bank?", "How far is it from here?"));

        l.add(new Scene("hotel", "酒店入住", "A2", "办理入住、要求换房、延迟退房")
                .line("Good evening. Do you have a reservation with us?", "报预订", "yes", "reservation", "under the name")
                .line("Could I see your passport, please?", "递证件", "here you are", "sure", "here it is")
                .line("You're in room 508. Breakfast is from 7 to 10.", "问 wifi 密码", "what's the wifi password", "wifi")
                .line("It's on the card in your room. Anything else?", "问能不能延迟退房", "late check-out", "could I", "noon")
                .phrase("I have a reservation under the name Li.",
                        "Could I have a late check-out?", "What time is breakfast served?"));

        l.add(new Scene("doctor", "看医生", "B1", "描述症状、理解医嘱")
                .line("What seems to be the problem?", "描述症状（多久了、什么感觉）", "I've had", "for", "days", "sore throat")
                .line("Any fever or cough?", "回答有无", "no fever", "a slight cough", "yes")
                .line("I'll prescribe some medicine. Take it twice a day after meals.", "复述用药方法确认", "twice a day", "after meals", "so I take")
                .line("Come back if it doesn't improve in three days.", "道谢并确认", "thank you", "if it gets worse", "see you")
                .phrase("I've had a headache for two days.", "I'm allergic to penicillin.",
                        "Should I take it with food?"));

        l.add(new Scene("restaurant", "餐厅点餐", "A2", "订位、点菜、处理上错菜")
                .line("Good evening. Do you have a reservation?", "说没有，问要等多久", "no", "how long", "wait", "table for two")
                .line("About fifteen minutes. Would you like to wait at the bar?", "同意", "sure", "that's fine", "yes please")
                .line("Are you ready to order?", "点主菜 + 一个忌口说明", "I'll have", "no", "I'm allergic", "on the side")
                .line("Sorry, I think I brought you the wrong dish.", "礼貌指出", "actually", "I ordered", "could you")
                .phrase("A table for two, please.", "I'll have the grilled salmon.",
                        "I'm allergic to nuts.", "I think this isn't what I ordered."));

        l.add(new Scene("airport", "机场值机", "B1", "办登机、行李超重、转机")
                .line("May I see your ticket and ID, please?", "递上证件", "here you are", "sure")
                .line("Are you checking any bags today?", "说托运一件", "one bag", "check in", "carry-on")
                .line("This bag is three kilos over the limit.", "问怎么办（付费还是拿出来）", "how much", "extra fee", "take out")
                .line("Your gate is B12. Boarding starts at 3:40.", "确认登机口和时间", "gate", "boarding", "3:40")
                .phrase("I'd like to check in one bag.", "How much is the extra baggage fee?",
                        "Where is the transfer counter?"));

        l.add(new Scene("smalltalk", "日常闲聊", "A2", "跟同事/邻居自然寒暄，不冷场")
                .line("Hey! How's your week going?", "回答 + 反问一句", "pretty good", "busy", "how about you")
                .line("Same here, it's been crazy. Any plans for the weekend?", "说一个计划", "I'm going to", "planning to", "probably")
                .line("Nice! I've been meaning to try that.", "顺势邀请或推荐", "you should", "want to join", "let me know")
                .line("Sounds good. I'll text you later.", "收尾", "sounds good", "see you", "talk later")
                .phrase("Pretty good, busy but fine. How about you?",
                        "I'm planning to stay home and relax.", "Let me know if you want to join."));

        all = l;
        return all;
    }

    /** 跟读句库：三档难度，每档 4 句。 */
    public static final String[][] SHADOW = {
            {"入门", "I get up at seven every morning.",
                    "There is a small shop near my house.",
                    "Can I have a cup of coffee, please?",
                    "The weather is really nice today."},
            {"基础", "I usually take the bus to work, but today I walked.",
                    "If it rains tomorrow, we will stay at home and watch a movie.",
                    "She has been learning English for about three years.",
                    "Could you tell me how to get to the nearest subway station?"},
            {"进阶", "The thing I like most about this city is how quiet it gets at night.",
                    "I didn't expect the meeting to last that long, but it was worth it.",
                    "What surprised me was not the price, but how hard it was to find.",
                    "Most people underestimate how much sleep actually affects their memory."}
    };

    private Scenes() {
    }
}
