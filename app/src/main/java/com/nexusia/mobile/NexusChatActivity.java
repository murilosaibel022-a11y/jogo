package com.nexusia.mobile;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** NEXUS IA: conversa integrada. Os modelos nunca são escolhidos manualmente no Android. */
public final class NexusChatActivity extends Activity {
    private static final int BACK=0xff090B12, SURFACE=0xff171B29, SOFT=0xff23293A,
            WHITE=0xffF5F6FB, MUTED=0xffA8AEC0, CYAN=0xff75DBF7, VIOLET=0xffB38CFC,
            OUTLINE=0xff343A4B, GREEN=0xff78E9BF, ORANGE=0xffFFAF6A;
    private static final int PICK=241, SPEECH=242, MIC_PERMISSION=243;
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final List<ChatLine> transcript=new ArrayList<>();
    private LinearLayout shell, messagesHost, footer, composerRow;
    private ScrollView scroller;
    private EditText messageField;
    private TextView stateLabel, attachLabel;
    private SharedPreferences settings;
    private SecretStore pairing;
    private LocalModelManager localModel;
    private boolean localReady=false;
    private TextToSpeech tts;
    private boolean speechReady=false, voiceMode=false, working=false, linked=false;
    private String projectId="", pendingName="", pendingMime="", pendingContent="", pendingBase64="";
    private String currentPage="chat";
    private OrbitLogo logo;
    private android.speech.SpeechRecognizer speechRecognizer;
    private android.app.Dialog voiceDialog;
    private TextView voiceStatus;
    private boolean voiceStarting=false;
    private int messageSerial=0;
    private static class ChatLine {
        final String role,text;
        ChatLine(String r,String t){role=r;text=t;}
    }
    interface Callback{void ok(JSONObject obj);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BACK);getWindow().setNavigationBarColor(BACK);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        settings=getSharedPreferences("nexus_assistant",MODE_PRIVATE);
        pairing=new SecretStore(this);
        localModel=new LocalModelManager(this);
        localReady=localModel.ready();
        restoreTranscript();
        tts=new TextToSpeech(this, status->{
            speechReady=status==TextToSpeech.SUCCESS;
            if(speechReady)tts.setLanguage(new Locale("pt","BR"));
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){runOnUiThread(()->{
                if(voiceMode&&currentPage.equals("chat")&&!isFinishing()&&voiceDialog!=null&&voiceDialog.isShowing())new Handler(Looper.getMainLooper()).postDelayed(()->listen(),500);
            });}
            @Override public void onError(String id){runOnUiThread(()->voiceMode=false);}
        });
        showChat();
        if(!pairing.host().isEmpty()) connectStored();
    }
    @Override protected void onDestroy(){
        voiceMode=false; if(tts!=null){tts.stop();tts.shutdown();}
        if(speechRecognizer!=null){speechRecognizer.cancel();speechRecognizer.destroy();speechRecognizer=null;}
        if(voiceDialog!=null)voiceDialog.dismiss();
        network.shutdownNow();super.onDestroy();
    }
    int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+0.5f);}
    GradientDrawable rounded(int fill,int stroke,int radius){
        GradientDrawable b=new GradientDrawable();b.setColor(fill);b.setCornerRadius(dp(radius));
        if(stroke!=0)b.setStroke(dp(1),stroke);return b;
    }
    GradientDrawable gradient(int a,int b,int radius){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{a,b});
        d.setCornerRadius(dp(radius));return d;
    }
    LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        t.setLineSpacing(dp(3),1.05f);return t;
    }
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    void addGap(LinearLayout parent,int size){View v=new View(this);parent.addView(v,lp(1,dp(size)));}
    void appear(View v,int delay){
        v.setAlpha(0);v.setTranslationY(dp(12));
        v.animate().alpha(1).translationY(0).setStartDelay(delay).setDuration(320).start();
    }
    TextView pill(String label,int bg,int fg){
        TextView v=text(label,12,fg,true);v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12),dp(7),dp(12),dp(7));v.setBackground(rounded(bg,0,30));return v;
    }
    TextView tap(String symbol,int size,Runnable task){
        TextView v=text(symbol,size,WHITE,true);v.setGravity(Gravity.CENTER);
        v.setBackground(rounded(SOFT,OUTLINE,16));v.setOnClickListener(x->task.run());
        return v;
    }
    void base(String title,String sub){
        shell=vertical();shell.setBackgroundColor(BACK);setContentView(shell);
        if(Build.VERSION.SDK_INT>=35){
            shell.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(i.left,i.top,i.right,i.bottom);
                return insets;
            });
        }
        LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16),dp(8),dp(16),dp(8));head.setBackgroundColor(BACK);
        shell.addView(head,lp(-1,dp(69)));
        logo=new OrbitLogo();head.addView(logo,lp(dp(44),dp(44)));
        LinearLayout names=vertical();
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);
        np.leftMargin=dp(10);head.addView(names,np);
        names.addView(text(title,18,WHITE,true));
        stateLabel=text(sub,11,MUTED,false);names.addView(stateLabel);
        TextView fresh=headerControl("＋",()->newChat());
        fresh.setContentDescription("Nova conversa");
        head.addView(fresh,lp(dp(38),dp(40)));
        TextView more=headerControl("⋮",()->showActions());
        more.setContentDescription("Mais opções");
        LinearLayout.LayoutParams ml=lp(dp(38),dp(40));ml.leftMargin=dp(5);head.addView(more,ml);
        View line=new View(this);line.setBackgroundColor(0xff232633);
        shell.addView(line,lp(-1,dp(1)));
    }
    TextView headerControl(String symbol,Runnable click){
        TextView v=text(symbol,24,MUTED,false);
        v.setGravity(Gravity.CENTER);v.setBackground(rounded(BACK,0,12));
        v.setOnClickListener(x->click.run());return v;
    }
    void newChat(){
        if(working){toast("A NEXUS está respondendo.");return;}
        if(transcript.isEmpty()){showChat();return;}
        new AlertDialog.Builder(this).setTitle("Nova conversa")
            .setMessage("Começar uma conversa vazia neste celular? O histórico anterior será limpo aqui.")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Criar",(d,w)->{transcript.clear();persistTranscript();showChat();}).show();
    }
    void showActions(){
        String[] options={"Projetos e programação","Configurar IA local","Conectar computador","Nova conversa"};
        new AlertDialog.Builder(this).setTitle("NEXUS IA").setItems(options,(d,index)->{
            switch(index){
                case 0: startActivity(new Intent(this,MainActivity.class));break;
                case 1: setupLocal();break;
                case 2: showConnection();break;
                case 3: newChat();break;
            }
        }).show();
    }
    void bottom(String selected){
        LinearLayout bar=new LinearLayout(this);bar.setPadding(dp(12),dp(7),dp(12),dp(8));
        bar.setBackgroundColor(SURFACE);shell.addView(bar,lp(-1,dp(65)));
        nav(bar,"◉","Conversa","chat",selected,()->showChat());
        nav(bar,"◈","Studio","studio",selected,()->{
            voiceMode=false;if(tts!=null)tts.stop();
            startActivity(new Intent(this,MainActivity.class));
        });
        nav(bar,"⌁","Conexão","connect",selected,()->showConnection());
    }
    void nav(LinearLayout row,String icon,String label,String id,String selected,Runnable click){
        LinearLayout item=vertical();item.setGravity(Gravity.CENTER);
        row.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        TextView symbol=text(icon,22,selected.equals(id)?CYAN:MUTED,true);
        symbol.setGravity(Gravity.CENTER);item.addView(symbol);
        TextView name=text(label,11,selected.equals(id)?WHITE:MUTED,selected.equals(id));
        name.setGravity(Gravity.CENTER);item.addView(name);
        item.setOnClickListener(v->click.run());
    }
    void showChat(){
        currentPage="chat";
        base("NEXUS IA",localReady?"● IA local disponível":linked?"● Computador conectado":"● Ativar IA local em Configurações");
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));scroller=scroll;
        messagesHost=vertical();messagesHost.setPadding(dp(17),dp(8),dp(17),dp(22));
        scroll.addView(messagesHost,new ScrollView.LayoutParams(-1,-2));
        if(transcript.isEmpty())welcome();
        else for(ChatLine line:transcript)addBubble(line.role,line.text,false);
        footer=vertical();footer.setPadding(dp(12),dp(7),dp(12),dp(11));
        footer.setBackgroundColor(BACK);shell.addView(footer,lp(-1,-2));
        attachLabel=text("",12,CYAN,false);
        if(!pendingName.isEmpty())attachLabel.setText("📎 "+pendingName+"  ×");
        attachLabel.setVisibility(pendingName.isEmpty()?View.GONE:View.VISIBLE);
        attachLabel.setPadding(dp(12),dp(4),0,dp(7));
        attachLabel.setOnClickListener(v->clearAttachment());footer.addView(attachLabel);
        composerRow=new LinearLayout(this);composerRow.setGravity(Gravity.CENTER_VERTICAL);
        composerRow.setBackground(rounded(SURFACE,OUTLINE,22));
        composerRow.setPadding(dp(6),dp(5),dp(6),dp(5));footer.addView(composerRow);
        TextView file=tap("+",25,()->pickAttachment());
        file.setContentDescription("Anexar arquivo");file.setTextColor(WHITE);
        file.setBackground(rounded(SURFACE,0,18));composerRow.addView(file,lp(dp(39),dp(46)));
        messageField=new EditText(this);
        messageField.setTextColor(WHITE);messageField.setHintTextColor(0xff919BAD);
        messageField.setTextSize(16);messageField.setHint("Mensagem");
        messageField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        messageField.setBackgroundColor(Color.TRANSPARENT);
        messageField.setMinHeight(dp(44));messageField.setMaxLines(6);
        messageField.setPadding(dp(7),0,dp(4),0);
        composerRow.addView(messageField,new LinearLayout.LayoutParams(0,-2,1));
        TextView voice=tap("◉",23,()->startVoiceConversation());
        voice.setContentDescription("Iniciar conversa por voz");
        voice.setTextColor(CYAN);voice.setBackground(rounded(SOFT,0,16));
        composerRow.addView(voice,lp(dp(43),dp(44)));
        TextView send=tap("↑",25,()->send());send.setContentDescription("Enviar mensagem");
        send.setBackground(gradient(0xff6B8DFF,0xff9169F4,17));
        LinearLayout.LayoutParams sp=lp(dp(43),dp(44));sp.leftMargin=dp(5);composerRow.addView(send,sp);
        TextView hint=text("Conversas locais permanecem neste dispositivo",10,MUTED,false);
        hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(7),0,0);
        footer.addView(hint);
        scrollBottom();
    }
    void welcome(){
        LinearLayout root=vertical();root.setGravity(Gravity.CENTER);
        root.setPadding(dp(12),dp(45),dp(12),dp(14));
        messagesHost.addView(root,new LinearLayout.LayoutParams(-1,-1));
        OrbitLogo image=new OrbitLogo();
        LinearLayout.LayoutParams icon=lp(dp(100),dp(100));icon.gravity=Gravity.CENTER;
        root.addView(image,icon);addGap(root,18);
        TextView h=text("NEXUS IA",24,WHITE,true);h.setGravity(Gravity.CENTER);root.addView(h);
        TextView intro=text("Um assistente para conversar, programar e criar.",14,MUTED,false);
        intro.setGravity(Gravity.CENTER);intro.setPadding(dp(20),dp(10),dp(20),dp(5));root.addView(intro);
        if(!localReady){
            addGap(root,22);
            TextView status=pill("Ativar IA neste celular  ›",0xff202638,CYAN);
            status.setPadding(dp(18),dp(12),dp(18),dp(12));
            status.setOnClickListener(v->setupLocal());
            LinearLayout.LayoutParams sl=lp(-2,-2);sl.gravity=Gravity.CENTER;root.addView(status,sl);
        }
        appear(root,0);
    }
    void addBubble(String role,String content,boolean animate){
        boolean mine=role.equals("user"),system=role.equals("system");
        LinearLayout row=new LinearLayout(this);row.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);
        LinearLayout.LayoutParams rp=lp(-1,-2);rp.bottomMargin=dp(15);
        messagesHost.addView(row,rp);
        LinearLayout bubble=vertical();bubble.setPadding(dp(16),dp(13),dp(16),dp(13));
        bubble.setBackground(rounded(mine?0xff292451:(system?0xff172238:SURFACE),
                mine?0xff413675:OUTLINE,20));
        row.addView(bubble,new LinearLayout.LayoutParams(-2,-2));
        TextView who=text(mine?"VOCÊ":system?"SISTEMA":"✧ NEXUS",10,mine?0xffC2B8FF:CYAN,true);
        bubble.addView(who);addGap(bubble,5);
        TextView t=text(content,15,WHITE,false);t.setMaxWidth(dp(310));t.setTextIsSelectable(true);
        bubble.addView(t);
        if(!mine&&!system){
            TextView replay=text("Ouvir  ♬",12,CYAN,true);replay.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams play=lp(-1,dp(26));play.topMargin=dp(7);
            bubble.addView(replay,play);replay.setOnClickListener(v->speak(content));
        }
        if(animate)appear(row,0);
        scrollBottom();
    }
    void scrollBottom(){if(scroller!=null)scroller.post(()->scroller.fullScroll(View.FOCUS_DOWN));}
    void persistTranscript(){
        JSONArray arr=new JSONArray();
        int from=Math.max(0,transcript.size()-60);
        for(int i=from;i<transcript.size();i++){ChatLine m=transcript.get(i);
            JSONObject j=new JSONObject();try{j.put("role",m.role);j.put("content",m.text);}catch(Exception ignored){}
            arr.put(j);
        }
        settings.edit().putString("history",arr.toString()).apply();
    }
    void restoreTranscript(){
        try{JSONArray arr=new JSONArray(settings.getString("history","[]"));
            for(int i=0;i<arr.length();i++){JSONObject o=arr.optJSONObject(i);
                if(o!=null)transcript.add(new ChatLine(o.optString("role"),o.optString("content")));}
        }catch(Exception ignored){}
    }
    void record(String role,String content){
        transcript.add(new ChatLine(role,content));persistTranscript();
        if(currentPage.equals("chat"))addBubble(role,content,true);
    }
    void setStatus(String s){if(stateLabel!=null)stateLabel.setText(s);}
    void startBusy(){
        working=true;setStatus("✧ NEXUS está pensando...");
        if(currentPage.equals("chat")){
            TextView loading=pill("✧ Preparando resposta  • • •",SURFACE,CYAN);
            messagesHost.addView(loading);
            loading.setTag("loading");loading.animate().alpha(0.4f).setDuration(700).withEndAction(()->{
                if(working&&loading.getParent()!=null)loading.animate().alpha(1).setDuration(700).start();
            }).start();scrollBottom();
        }
    }
    void stopBusy(){
        working=false;setStatus(localReady?"● IA local • pronta e offline":linked?"● PC conectado":"● Ative a IA no celular");
        if(messagesHost!=null){for(int i=messagesHost.getChildCount()-1;i>=0;i--){
            View x=messagesHost.getChildAt(i);if("loading".equals(x.getTag()))messagesHost.removeViewAt(i);
        }}
    }
    void send(){
        if(working)return;
        String value=messageField.getText().toString().trim();
        if(value.isEmpty()&&pendingName.isEmpty())return;
        if(localReady){
            if(!pendingBase64.isEmpty()){
                new AlertDialog.Builder(this).setTitle("Imagem anexada").setMessage("O modelo local desta versão entende texto e código, não imagens. Para analisar fotos, é necessário instalar um modelo visual compatível em uma próxima atualização.").setPositiveButton("OK",null).show();
                return;
            }
            sendLocal(value,pendingName,pendingContent);
            return;
        }
        if(!linked||projectId.isEmpty()){
            if(!value.isEmpty())messageField.setText(value);
            new AlertDialog.Builder(this).setTitle("Instale a IA local")
                .setMessage("A NEXUS IA pode funcionar no próprio celular. É necessário instalar uma vez o modelo de aproximadamente 484 MB.")
                .setNegativeButton("Agora não",null).setPositiveButton("Instalar",(d,w)->setupLocal()).show();
            return;
        }
        String display=value.isEmpty()?"📎 "+pendingName:value;
        if(!pendingName.isEmpty())display+="\n📎 "+pendingName;
        record("user",display);messageField.setText("");
        final String request=value,filename=pendingName,mime=pendingMime,content=pendingContent,image=pendingBase64;
        clearAttachment();startBusy();
        network.execute(()->{
            try{
                JSONObject data=new JSONObject();data.put("message",request.isEmpty()?"Analise o anexo enviado.":request);
                if(!filename.isEmpty()){
                    JSONArray arr=new JSONArray();JSONObject file=new JSONObject();
                    file.put("name",filename);file.put("mime",mime);
                    if(!image.isEmpty())file.put("base64",image);
                    else file.put("text",content);
                    arr.put(file);data.put("attachments",arr);
                }
                JSONObject result=api("POST","/api/projects/"+projectId+"/chat",data);
                String reply=result.optString("reply","");
                runOnUiThread(()->{stopBusy();record("assistant",reply.isEmpty()?"O modelo não retornou texto.":reply);
                    if(voiceMode)speak(reply);});
            }catch(Exception ex){runOnUiThread(()->{
                stopBusy();record("system","Não consegui obter uma resposta: "+ex.getMessage());
                voiceMode=false;});}
        });
    }

    void setupLocal(){
        if(localReady){toast("A NEXUS IA já está instalada no seu celular.");return;}
        if(localModel.downloading()){toast("Download da IA em andamento.");return;}
        new AlertDialog.Builder(this)
            .setTitle("Instalar IA independente")
            .setMessage("A NEXUS IA vai baixar cerca de 484 MB pelo Wi-Fi (modelo Qwen3 0.6B). Precisa de aproximadamente 650 MB de espaço livre. Depois da primeira instalação, o chat funciona offline e sem computador. Esta versão local é compacta e não tem a mesma capacidade de modelos grandes na nuvem.\n\nDeseja instalar?")
            .setNegativeButton("Cancelar",null)
            .setPositiveButton("Baixar e ativar",(dialog,which)->{
                startBusy();
                localModel.install(new LocalModelManager.Listener(){
                    @Override public void progress(int percent,String value){
                        runOnUiThread(()->setStatus("↓ "+percent+"% • "+value));
                    }
                    @Override public void ready(){
                        runOnUiThread(()->{
                            localReady=true;stopBusy();showChat();
                            toast("IA instalada. Agora a NEXUS funciona sem PC.");
                        });
                    }
                    @Override public void error(String problem){
                        runOnUiThread(()->{
                            stopBusy();new AlertDialog.Builder(NexusChatActivity.this)
                                .setTitle("A instalação não foi concluída")
                                .setMessage(problem+"\n\nO download parcial pode ser retomado.")
                                .setPositiveButton("Entendi",null).show();
                        });
                    }
                });
            }).show();
    }
    void sendLocal(String question,String name,String attachment){
        StringBuilder all=new StringBuilder(question);
        if(!name.isEmpty()&&!attachment.isEmpty()){
            if(all.length()>0)all.append("\n\n");
            all.append("Conteúdo do arquivo ").append(name).append(":\n")
                .append(attachment.substring(0,Math.min(attachment.length(),10000)));
        }
        final String value=all.toString();
        String display=question.isEmpty()?("Analise "+name):question;
        if(!name.isEmpty())display+="\n📎 "+name;
        record("user",display);messageField.setText("");
        clearAttachment();startBusy();
        final String prompt=localPrompt(value);
        network.execute(()->{
            try{
                String answer=NativeEngine.generate(localModel.path(),prompt);
                runOnUiThread(()->{
                    stopBusy();record("assistant",answer);
                    if(voiceMode)speak(answer);
                });
            }catch(Throwable e){
                runOnUiThread(()->{
                    stopBusy();
                    record("system","Não consegui executar o modelo local: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
                    voiceMode=false;
                });
            }
        });
    }
    String localPrompt(String current){
        StringBuilder out=new StringBuilder();
        out.append("<|im_start|>system\n");
        out.append("Você é NEXUS IA, um assistente próprio para português brasileiro, jogos e programação. ");
        out.append("Fale diretamente com o usuário, com clareza e naturalidade. ");
        out.append("Crie código real quando solicitado, explique erros, ajude a projetar jogos. ");
        out.append("Você está rodando offline no celular e não tem internet nem acesso a programas externos. ");
        out.append("Não finja executar ferramentas ou criar arquivos quando não tiver feito isso. ");
        out.append("Seu modelo é compacto: admita quando não souber. Responda de forma útil e concisa.\n");
        out.append("<|im_end|>\n");
        int from=Math.max(0,transcript.size()-5);
        for(int i=from;i<transcript.size()-1;i++){
            ChatLine line=transcript.get(i);
            if(!line.role.equals("user")&&!line.role.equals("assistant"))continue;
            String t=line.text;
            if(t.length()>650)t=t.substring(t.length()-650);
            out.append("<|im_start|>").append(line.role).append("\n")
               .append(t.replace("<|im_start|>","").replace("<|im_end|>",""))
               .append("\n<|im_end|>\n");
        }
        String bounded=current.length()>4800?current.substring(current.length()-4800):current;
        out.append("<|im_start|>user\n")
            .append(bounded.replace("<|im_start|>","").replace("<|im_end|>",""))
            .append("\n/no_think\n<|im_end|>\n<|im_start|>assistant\n");
        return out.toString();
    }

    void clearAttachment(){
        pendingName="";pendingMime="";pendingContent="";pendingBase64="";
        if(attachLabel!=null){attachLabel.setVisibility(View.GONE);attachLabel.setText("");}
    }
    void pickAttachment(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,PICK);
    }
    void attach(Uri uri){
        try{
            String name="arquivo";String mime=getContentResolver().getType(uri);
            try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                if(c!=null&&c.moveToFirst())name=c.getString(0);
            }
            String lower=name.toLowerCase(Locale.ROOT);
            boolean image=(mime!=null&&mime.startsWith("image/"));
            boolean textFile=(mime!=null&&(mime.startsWith("text/")||mime.contains("json")||mime.contains("xml")))
                    ||lower.matches(".*\\.(py|gd|js|ts|tsx|jsx|lua|rs|java|cs|cpp|h|md|txt|html|css|json|yaml|yml|xml|sql|log|csv)$");
            if(!image&&!textFile){
                new AlertDialog.Builder(this).setTitle("Arquivo ainda não suportado")
                    .setMessage("Esta versão aceita imagens e arquivos de texto/código. PDFs e outros formatos exigem um extrator adicional.")
                    .setPositiveButton("OK",null).show();return;
            }
            ByteArrayOutputStream buf=new ByteArrayOutputStream();
            try(InputStream stream=getContentResolver().openInputStream(uri)){
                if(stream==null)throw new Exception("Arquivo inacessível");
                byte[] chunk=new byte[8192];int n;
                while((n=stream.read(chunk))!=-1){
                    if(buf.size()+n>(image?1_250_000:48_000))throw new Exception("Arquivo grande demais (imagem: 1,25 MB; texto: 48 KB)");
                    buf.write(chunk,0,n);
                }
            }
            pendingName=name;pendingMime=mime==null?(image?"image/jpeg":"text/plain"):mime;
            if(image)pendingBase64=Base64.encodeToString(buf.toByteArray(),Base64.NO_WRAP);
            else pendingContent=new String(buf.toByteArray(),StandardCharsets.UTF_8);
            attachLabel.setText("📎 "+name+"  ×");attachLabel.setVisibility(View.VISIBLE);
        }catch(Exception ex){toast("Anexo: "+ex.getMessage());}
    }
    void startVoiceConversation(){
        if(!localReady&&!linked){
            new AlertDialog.Builder(this).setTitle("Voz da NEXUS")
                .setMessage("Ative a IA no celular para conversar por voz sem depender do PC.")
                .setNegativeButton("Agora não",null)
                .setPositiveButton("Ativar IA",(d,w)->setupLocal()).show();
            return;
        }
        if(getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)==false){
            toast("Este aparelho não tem microfone disponível.");return;
        }
        if(Build.VERSION.SDK_INT>=23&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION);return;
        }
        voiceMode=true;
        voiceDialog=new android.app.Dialog(this);
        LinearLayout container=vertical();container.setBackgroundColor(BACK);
        container.setPadding(dp(24),dp(30),dp(24),dp(30));
        voiceDialog.setContentView(container);
        TextView top=text("NEXUS IA  •  VOZ",15,WHITE,true);
        top.setGravity(Gravity.CENTER);container.addView(top);
        LinearLayout.LayoutParams sp=lp(-1,0);sp.weight=1;
        LinearLayout center=vertical();center.setGravity(Gravity.CENTER);
        container.addView(center,sp);
        OrbitLogo orb=new OrbitLogo();
        LinearLayout.LayoutParams o=lp(dp(205),dp(205));o.gravity=Gravity.CENTER;
        center.addView(orb,o);
        addGap(center,28);
        voiceStatus=text("Toque no centro para falar",18,WHITE,true);
        voiceStatus.setGravity(Gravity.CENTER);center.addView(voiceStatus);
        addGap(center,12);
        TextView desc=text("Conversa direta, sem abrir a pesquisa do Android",13,MUTED,false);
        desc.setGravity(Gravity.CENTER);center.addView(desc);
        orb.setOnClickListener(v->listen());
        TextView close=pill("Encerrar conversa",SOFT,WHITE);
        close.setPadding(dp(20),dp(15),dp(20),dp(15));
        close.setGravity(Gravity.CENTER);close.setOnClickListener(v->closeVoice());
        container.addView(close,lp(-1,-2));
        voiceDialog.setOnDismissListener(d->{
            voiceMode=false;
            if(speechRecognizer!=null)speechRecognizer.cancel();
            if(tts!=null)tts.stop();voiceDialog=null;voiceStatus=null;
        });
        if(voiceDialog.getWindow()!=null){
            voiceDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            voiceDialog.getWindow().setLayout(-1,-1);
        }
        voiceDialog.show();
        voiceDialog.getWindow().setLayout(-1,-1);
        listen();
    }
    void closeVoice(){
        voiceMode=false;
        if(speechRecognizer!=null)speechRecognizer.cancel();
        if(tts!=null)tts.stop();
        if(voiceDialog!=null)voiceDialog.dismiss();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==MIC_PERMISSION){
            if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startVoiceConversation();
            else toast("Autorize o microfone nas configurações para conversar por voz.");
        }
    }
    void voiceLabel(String value){
        if(voiceStatus!=null)voiceStatus.setText(value);
    }
    void listen(){
        if(!voiceMode||working||voiceDialog==null||!voiceDialog.isShowing())return;
        if(!SpeechRecognizer.isRecognitionAvailable(this)){
            voiceLabel("Reconhecimento de voz indisponível neste celular");
            return;
        }
        try{
            if(speechRecognizer==null){
                if(Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this))
                    speechRecognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                else speechRecognizer=SpeechRecognizer.createSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new RecognitionListener(){
                    @Override public void onReadyForSpeech(Bundle params){voiceLabel("Ouvindo você...");}
                    @Override public void onBeginningOfSpeech(){voiceLabel("Pode falar. Estou ouvindo.");}
                    @Override public void onRmsChanged(float rms){}
                    @Override public void onBufferReceived(byte[] buf){}
                    @Override public void onEndOfSpeech(){voiceLabel("Entendi. Processando...");}
                    @Override public void onError(int error){
                        if(voiceMode)voiceLabel("Não ouvi bem. Toque no centro para tentar novamente.");
                    }
                    @Override public void onResults(Bundle results){
                        ArrayList<String> heard=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if(heard!=null&&!heard.isEmpty()&&voiceMode){
                            voiceLabel("NEXUS está respondendo...");
                            messageField.setText(heard.get(0));
                            send();
                        }else if(voiceMode)voiceLabel("Toque no centro para repetir.");
                    }
                    @Override public void onPartialResults(Bundle results){
                        ArrayList<String> parts=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if(parts!=null&&!parts.isEmpty())voiceLabel(parts.get(0));
                    }
                    @Override public void onEvent(int event,Bundle params){}
                });
            }
            android.content.Intent intent=new android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            voiceLabel("Preparando escuta...");
            speechRecognizer.startListening(intent);
        }catch(Exception e){voiceLabel("Falha no microfone: "+e.getMessage());}
    }
    void speak(String value){
        if(!speechReady||value==null||value.isEmpty())return;
        tts.speak(value,TextToSpeech.QUEUE_FLUSH,null,"nexus_reply_"+(++messageSerial));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK){if(request==SPEECH)voiceMode=false;return;}
        if(request==PICK&&data!=null&&data.getData()!=null)attach(data.getData());
        if(request==SPEECH&&data!=null){
            ArrayList<String> options=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(options!=null&&!options.isEmpty()&&currentPage.equals("chat")){
                messageField.setText(options.get(0));send();
            }else voiceMode=false;
        }
    }
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void showConnection(){
        voiceMode=false;currentPage="connect";
        base("NEXUS Link","Conexão segura por pareamento • Wi-Fi local");
        ScrollView scroll=new ScrollView(this);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=vertical();body.setPadding(dp(20),dp(22),dp(20),dp(20));scroll.addView(body);
        TextView title=text("Uma IA. Seus dispositivos.",27,WHITE,true);body.addView(title);
        addGap(body,10);
        body.addView(text("Seu computador executa os modelos e os agentes. O celular é a interface de conversa, voz e criação.",15,MUTED,false));
        addGap(body,23);
        LinearLayout card=vertical();card.setPadding(dp(19),dp(18),dp(19),dp(19));
        card.setBackground(rounded(SURFACE,OUTLINE,24));body.addView(card);
        card.addView(text("Conectar ao NEXUS Companion",18,WHITE,true));addGap(card,11);
        EditText host=new EditText(this);styleInput(host,"IP do PC:porta, ex.: 192.168.1.20:8877",pairing.host());
        card.addView(host,lp(-1,dp(56)));addGap(card,11);
        EditText key=new EditText(this);styleInput(key,"Código de pareamento","");
        key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(key,lp(-1,dp(56)));addGap(card,17);
        TextView connect=pill("Conectar agora",0xff5342B5,WHITE);connect.setPadding(dp(18),dp(15),dp(18),dp(15));
        connect.setOnClickListener(v->{
            String h=host.getText().toString().trim(),t=key.getText().toString().trim();
            if(!safeAddress(h)||t.length()<20){toast("Confira o IP privado e o código de pareamento.");return;}
            setStatus("Conectando...");
            network.execute(()->{
                try{
                    JSONObject ok=apiWith(h,t,"GET","/api/health",null);
                    pairing.setHost(h);pairing.saveToken(t);
                    String pid=ensureProject(h,t);autoModel(h,t);
                    projectId=pid;linked=true;
                    runOnUiThread(()->{toast("NEXUS conectada.");showChat();});
                    syncHistory();
                }catch(Exception ex){runOnUiThread(()->toast("Conexão: "+ex.getMessage()));}
            });
        });card.addView(connect);
        addGap(body,14);
        body.addView(text("No PC: execute python -m nexus.mobile_bridge --lan e copie o endereço e código exibidos.",13,MUTED,false));
        addGap(body,15);
        body.addView(text("Privacidade: o Companion usa HTTP na rede local. Conecte apenas a redes Wi-Fi confiáveis; não exponha sua porta à internet.",12,0xffDCCB9B,false));
        if(linked){
            addGap(body,15);
            TextView disconnect=pill("Desconectar dispositivo",SOFT,WHITE);
            disconnect.setOnClickListener(v->{pairing.clear();linked=false;projectId="";showConnection();});
            body.addView(disconnect);
        }
        bottom("connect");
    }
    void styleInput(EditText edit,String hint,String value){
        edit.setTextColor(WHITE);edit.setHintTextColor(MUTED);edit.setTextSize(14);
        edit.setHint(hint);edit.setText(value);edit.setSingleLine(true);
        edit.setPadding(dp(15),0,dp(12),0);edit.setBackground(rounded(0xff0B1222,OUTLINE,14));
    }
    boolean safeAddress(String raw){
        if(!raw.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{2,5}"))return false;
        try{
            String[] parts=raw.split(":");int port=Integer.parseInt(parts[1]);
            if(port<1||port>65535)return false;
            String[] octets=parts[0].split("\\.");int[] a=new int[4];
            for(int i=0;i<4;i++){a[i]=Integer.parseInt(octets[i]);if(a[i]>255)return false;}
            return a[0]==10||a[0]==192&&a[1]==168||a[0]==172&&a[1]>=16&&a[1]<=31;
        }catch(Exception e){return false;}
    }
    JSONObject api(String verb,String path,JSONObject data)throws Exception{
        return apiWith(pairing.host(),pairing.token(),verb,path,data);
    }
    JSONObject apiWith(String host,String token,String verb,String path,JSONObject data)throws Exception{
        if(!safeAddress(host)||!path.startsWith("/api/"))throw new Exception("Endereço não permitido.");
        HttpURLConnection connection=(HttpURLConnection)new URL("http://"+host+path).openConnection();
        connection.setConnectTimeout(8500);connection.setReadTimeout(path.endsWith("/chat")?305000:18000);
        connection.setRequestMethod(verb);connection.setRequestProperty("Authorization","Bearer "+token);
        connection.setRequestProperty("Accept","application/json");connection.setUseCaches(false);
        if(data!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
            try(java.io.OutputStream out=connection.getOutputStream()){out.write(bytes);}
        }
        try{
            int code=connection.getResponseCode();
            InputStream raw=code>=400?connection.getErrorStream():connection.getInputStream();
            if(raw==null)throw new Exception("Servidor sem resposta.");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
            try(InputStream in=raw){while((n=in.read(b))>=0){
                if(out.size()+n>5_000_000)throw new Exception("Resposta muito grande");
                out.write(b,0,n);
            }}
            JSONObject result=new JSONObject(out.toString("UTF-8"));
            if(code>=400)throw new Exception(result.optString("error","Erro HTTP "+code));
            return result;
        }finally{connection.disconnect();}
    }
    String ensureProject(String host,String token)throws Exception{
        JSONObject out=apiWith(host,token,"GET","/api/projects",null);
        JSONArray arr=out.optJSONArray("projects");
        if(arr!=null)for(int i=0;i<arr.length();i++){
            JSONObject item=arr.optJSONObject(i);
            if(item!=null&&"NEXUS Conversas".equals(item.optString("name")))return item.optString("id");
        }
        JSONObject create=new JSONObject();create.put("name","NEXUS Conversas");create.put("kind","code");
        JSONObject j=apiWith(host,token,"POST","/api/projects",create);
        return j.getJSONObject("project").getString("id");
    }
    void autoModel(String host,String token)throws Exception{
        JSONObject conf=apiWith(host,token,"GET","/api/config",null);
        JSONArray options=conf.optJSONArray("available");
        if(options==null||options.length()==0)throw new Exception("Instale um modelo no Ollama do PC.");
        String top=options.optString(0),current=conf.optString("model","");
        int best=-10000;
        for(int i=0;i<options.length();i++){
            String m=options.optString(i);String n=m.toLowerCase(Locale.ROOT);int rank=0;
            if(n.contains("qwen"))rank+=30;if(n.contains("llama"))rank+=23;
            if(n.contains("gemma"))rank+=19;if(n.contains("coder"))rank+=8;
            if(n.contains("vision")||n.contains("vl"))rank+=6;
            if(n.contains("1b")||n.contains("0.5b"))rank-=16;
            if(n.contains("3b")||n.contains("4b"))rank+=7;
            if(n.contains("70b")||n.contains("32b"))rank-=15;
            if(rank>best){best=rank;top=m;}
        }
        if(!top.equals(current)){JSONObject set=new JSONObject();set.put("model",top);
            apiWith(host,token,"PUT","/api/config",set);}
    }
    void connectStored(){
        network.execute(()->{
            try{
                api("GET","/api/health",null);
                projectId=ensureProject(pairing.host(),pairing.token());
                autoModel(pairing.host(),pairing.token());
                linked=true;runOnUiThread(()->{if(currentPage.equals("chat"))showChat();});
                syncHistory();
            }catch(Exception ex){linked=false;runOnUiThread(()->{
                if(currentPage.equals("chat"))setStatus("● PC indisponível • toque em ⚙");
            });}
        });
    }
    void syncHistory(){
        try{
            JSONObject j=api("GET","/api/projects/"+projectId+"/messages",null);
            JSONArray messages=j.optJSONArray("messages");if(messages==null)return;
            ArrayList<ChatLine> fromPc=new ArrayList<>();
            int start=Math.max(0,messages.length()-45);
            for(int i=start;i<messages.length();i++){
                JSONObject m=messages.optJSONObject(i);if(m==null)continue;
                String role=m.optString("role");if(role.equals("user")||role.equals("assistant"))
                    fromPc.add(new ChatLine(role,m.optString("content")));
            }
            if(!fromPc.isEmpty())runOnUiThread(()->{
                if(working)return;transcript.clear();transcript.addAll(fromPc);
                persistTranscript();if(currentPage.equals("chat"))showChat();
            });
        }catch(Exception ignored){}
    }
    private final class OrbitLogo extends View {
        final Paint paint=new Paint(3);final RectF arc=new RectF();float rotation=0;
        final ValueAnimator animator=ValueAnimator.ofFloat(0f,360f);
        OrbitLogo(){
            super(NexusChatActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);
            animator.setDuration(13000);animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new android.view.animation.LinearInterpolator());
            animator.addUpdateListener(a->{rotation=(float)a.getAnimatedValue();invalidate();});animator.start();
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float size=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
            c.save();c.translate(cx,cy);c.rotate(rotation);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(size*0.055f);
            arc.set(-size*.38f,-size*.38f,size*.38f,size*.38f);
            paint.setColor(CYAN);c.drawArc(arc,12,145,false,paint);
            paint.setColor(VIOLET);c.drawArc(arc,193,142,false,paint);
            paint.setStrokeWidth(size*.035f);arc.set(-size*.27f,-size*.27f,size*.27f,size*.27f);
            paint.setColor(0xffA4B6FF);c.drawArc(arc,90,155,false,paint);
            c.restore();paint.setStyle(Paint.Style.FILL);
            paint.setShader(new android.graphics.RadialGradient(cx-size*.04f,cy-size*.07f,size*.22f,
                new int[]{0xffB8FFFF,CYAN,0xff5C54D6},null,android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx,cy,size*.16f,paint);paint.setShader(null);
        }
        @Override protected void onDetachedFromWindow(){animator.cancel();super.onDetachedFromWindow();}
    }
}