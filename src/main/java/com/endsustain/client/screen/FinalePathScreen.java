package com.endsustain.client.screen;

import com.endsustain.client.FinalePathClientState;
import com.endsustain.progress.FinalePathProgress;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public class FinalePathScreen extends Screen {
    private static final String[] TITLES = {"序章：愚者启程","第一幕：繁花之下皆是旧坟","第二幕：深渊仍记得上一场梦","第三幕：旧世界仍在燃烧","第四幕：世界的枷锁","第五幕：黑暗秘典","第六幕：末地不是终点","第七幕：窥探终末之语","终章：落幕仪式","最后一战","尾声：世界已经结束，而故事仍被记得"};
    private static final String[] CONDITIONS = {"佩戴小蘸酱","击败虚空之花","击败暗夜巫妖","击败先驱者","抵达下界","击败亚波伦","击败末影龙","击败末影守望者","完成落幕仪式","直面末影蘸酱","击败末影蘸酱"};
    private static final String[] SKILLS = {"虚空花冠","巫妖魂火","先驱之力","深渊回响","旧世余烬","天启之环"};
    private static final String[] STATS = {"生命 +10%  攻击 +5%","生命 +15%  攻击 +10%  移速 +5%","生命 +20%  攻击 +15%  护甲 +4","生命 +25%  攻击 +20%  减伤 5%","生命 +30%  攻击 +25%  韧性 +4  减伤 8%","生命 +40%  攻击 +35%  移速 +10%  减伤 15%"};
    private static final String[] SKILL_DESCRIPTIONS = {
            "N键 · 对最近敌对目标发动荆棘突刺 · 冷却50秒",
            "B键 · 召唤3只幻翼仆从，存在3分钟 · 冷却5分钟",
            "X键 · 命中后召唤多道激光 · 冷却50秒",
            "C键钩爪；G键切换固定目标触手 · 冷却100秒 · 水下呼吸/陆地回复II",
            "V键 · 在面朝方向召唤烈焰轰击 · 冷却2分钟",
            "M键 · 展开40格领域，锁定敌对目标并天降死亡箭（最多20支） · 冷却10分钟"
    };
    private int page, selected, chapterScroll, textScroll;
    public FinalePathScreen() { super(Component.translatable("screen.endsustain.finale_path")); }
    @Override public boolean isPauseScreen() { return false; }
    public void refresh() {}

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        renderBackground(g); int x = width / 2 - 160, y = height / 2 - 160, w = 320, h = 320;
        g.fill(x, y, x+w, y+h, 0xE8140B22); border(g,x,y,w,h,0xFF9D4DCE);
        g.drawCenteredString(font, title, width/2, y+9, 0xFFE5B8FF);
        tab(g,x+12,y+25,140,"终末进度",page==0); tab(g,x+168,y+25,140,"终末技能",page==1);
        if (page==0) renderStory(g,x,y); else renderSkills(g,x,y);
        super.render(g, mouseX, mouseY, partial);
    }

    private void renderStory(GuiGraphics g,int x,int y) {
        int mask=FinalePathClientState.storyMask, unlocked=Integer.bitCount(mask & 0x7FF);
        g.fill(x+12,y+48,x+112,y+306,0x77201030); g.fill(x+120,y+48,x+308,y+306,0x77201030);
        for(int i=0;i<11;i++){int yy=y+53+i*12-chapterScroll; if(yy<y+50||yy>y+297)continue; boolean ok=(mask&(1<<i))!=0; String text=ok?TITLES[i]:(i==unlocked?TITLES[i]:"？？？"); g.drawString(font,(selected==i?"> ":"")+text,x+16,yy,ok?0xFFDCA2FF:0xFF716778,false);}
        selected=Math.max(0,Math.min(selected,10)); boolean ok=(mask&(1<<selected))!=0;
        g.drawString(font,ok?TITLES[selected]:"尚未解锁",x+126,y+55,ok?0xFFE8C6FF:0xFF8A7C90,false);
        Component text=ok?Component.literal(com.endsustain.client.FinaleStoryData.get(selected)):Component.literal("解锁条件："+CONDITIONS[selected]);
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(text,174);
        int maxScroll=Math.max(0,lines.size()*11-222); textScroll=Math.max(0,Math.min(textScroll,maxScroll));
        g.enableScissor(x+124,y+68,x+304,y+297);
        int yy=y+70-textScroll; for(var line:lines){if(yy>=y+68&&yy<=y+294)g.drawString(font,line,x+126,yy,0xFFBFB3C7,false);yy+=11;}
        g.disableScissor();
        if(maxScroll>0)g.drawString(font,"滚轮浏览全文  "+(textScroll*100/Math.max(1,maxScroll))+"%",x+126,y+296,0xFF896D98,false);
        g.drawString(font,"终末进度："+unlocked+" / 11",x+14,y+309,0xFFBF7FE8,false);
    }

    private void renderSkills(GuiGraphics g,int x,int y) {
        int tier=FinalePathClientState.tier; g.drawString(font,"当前阶位："+(tier==0?"未觉醒":("VI".substring(0,0)+roman(tier)+" · "+SKILLS[tier-1])),x+16,y+52,0xFFE4B4FF,false);
        for(int i=0;i<6;i++){
            int yy=y+68+i*40;
            boolean active=i+1==tier, inherited=i+1<tier, questAndDrop=(FinalePathClientState.witnessMask&(1<<i))!=0;
            int color=active?0xFFEEB8FF:inherited?0xFFA775C2:0xFF766B7A;
            g.fill(x+16,yy-3,x+304,yy+35,active?0x663E1557:0x44201828);
            border(g,x+16,yy-3,288,38,color);
            ItemStack icon=new ItemStack(ForgeRegistries.ITEMS.getValue(FinalePathProgress.SKILL_DROPS[i]));
            g.renderItem(icon,x+20,yy+2);
            g.drawString(font,roman(i+1)+"  "+SKILLS[i],x+42,yy+1,color,false);
            g.drawString(font,STATS[i],x+132,yy+1,0xFFB8ACBF,false);
            g.drawString(font,inherited?"已继承":active?"当前生效":"任务 + "+(questAndDrop?"掉落已见证":"需获得掉落"),x+42,yy+13,color,false);
            if (i == 5) {
                g.drawString(font,"M键 · 40格领域锁敌，天降死亡箭×20",x+42,yy+22,0xFFC9B7D0,false);
                g.drawString(font,"50%闪避；清Buff/禁疗；狱火龙卷；四柱护体",x+42,yy+32,0xFFC9B7D0,false);
            } else {
                g.drawString(font,SKILL_DESCRIPTIONS[i],x+42,yy+24,0xFFC9B7D0,false);
            }
        }
    }

    private static String roman(int i){return new String[]{"","I","II","III","IV","V","VI"}[i];}
    private void tab(GuiGraphics g,int x,int y,int w,String s,boolean on){g.fill(x,y,x+w,y+18,on?0xAA6C2B91:0x66331D41);border(g,x,y,w,18,on?0xFFE0A5FF:0xFF725482);g.drawCenteredString(font,s,x+w/2,y+5,on?0xFFFFFFFF:0xFFB49EBC);}
    private static void border(GuiGraphics g,int x,int y,int w,int h,int c){g.fill(x,y,x+w,y+1,c);g.fill(x,y+h-1,x+w,y+h,c);g.fill(x,y,x+1,y+h,c);g.fill(x+w-1,y,x+w,y+h,c);}
    @Override public boolean mouseClicked(double mx,double my,int button){int x=width/2-160,y=height/2-160;if(my>=y+25&&my<y+43){page=mx<x+160?0:1;return true;}if(page==0&&mx>=x+12&&mx<x+112&&my>=y+48&&my<y+306){int old=selected;selected=Math.max(0,Math.min(10,(int)((my-(y+53)+chapterScroll)/12)));if(old!=selected)textScroll=0;return true;}return super.mouseClicked(mx,my,button);}
    @Override public boolean mouseScrolled(double mx,double my,double delta){if(page==0){int x=width/2-160;if(mx>=x+120){Component text=Component.literal(com.endsustain.client.FinaleStoryData.get(selected));int max=Math.max(0,font.split(text,174).size()*11-222);textScroll=Math.max(0,Math.min(max,textScroll-(int)(delta*22)));}else chapterScroll=Math.max(0,Math.min(20,chapterScroll-(int)(delta*12)));return true;}return false;}
}
