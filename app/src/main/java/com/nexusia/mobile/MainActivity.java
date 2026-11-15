package com.nexusia.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.*;

public class MainActivity extends Activity {
  final int bg=Color.rgb(8,13,25), card=Color.rgb(22,32,53), fg=Color.rgb(235,242,255), cyan=Color.rgb(87,223,243), purple=Color.rgb(114,82,225);
  LinearLayout main,stack,nav; String screen="home", current="", selected=""; EditText editor; String previous="";
  static final int EXPORT=12; File base;
  int d(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
  @Override public void onCreate(Bundle s){ super.onCreate(s);getWindow().setStatusBarColor(bg);getWindow().setNavigationBarColor(bg); base=new File(getFilesDir(),"projects");base.mkdirs();show("home"); }
  TextView label(String value,int size,boolean strong){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(fg);if(strong)t.setTypeface(null,Typeface.BOLD);t.setPadding(d(3),d(7),d(3),d(7));return t;}
  GradientDrawable shape(int color){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(d(16));return g;}
  LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
  LinearLayout panel(String title,String detail){LinearLayout p=column();p.setBackground(shape(card));p.setPadding(d(15),d(13),d(15),d(15));LinearLayout.LayoutParams par=new LinearLayout.LayoutParams(-1,-2);par.bottomMargin=d(12);stack.addView(p,par);p.addView(label(title,18,true));if(detail!=null)p.addView(label(detail,13,false));return p;}
  void button(LinearLayout parent,String title,Runnable work){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(fg);b.setBackground(shape(purple));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=d(9);parent.addView(b,lp);b.setOnClickListener(v->work.run());}
  EditText input(LinearLayout parent,String hint,String value,boolean multiline){EditText e=new EditText(this);e.setText(value);e.setHint(hint);e.setHintTextColor(0xffa0b1cc);e.setTextColor(fg);e.setTextSize(14);e.setBackground(shape(0xff121d33));e.setPadding(d(12),d(12),d(12),d(12));if(multiline){e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);e.setGravity(Gravity.TOP);e.setMinLines(12);}parent.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
  void show(String name){screen=name;main=column();main.setBackgroundColor(bg);setContentView(main);TextView brand=label("◆ NEXUS IA",27,true);brand.setTextColor(cyan);brand.setPadding(d(20),d(20),d(10),d(6));main.addView(brand);TextView sub=label("GAME STUDIO   •   CODE STUDIO",12,true);sub.setPadding(d(20),0,0,d(12));main.addView(sub);
    ScrollView scroll=new ScrollView(this);main.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));stack=column();stack.setPadding(d(16),d(12),d(16),d(20));scroll.addView(stack);
    if(name.equals("home"))home();else if(name.equals("projects"))projects();else if(name.equals("project"))project();else if(name.equals("editor"))edit();else if(name.equals("agents"))agents();
    TextView foot=label("Projetos locais • armazenamento privado do aplicativo",11,false);foot.setPadding(d(15),d(5),0,d(8));main.addView(foot);
    nav=new LinearLayout(this);nav.setPadding(d(6),d(7),d(6),d(9));nav.setBackgroundColor(card);main.addView(nav);
    tab("Início","home");tab("Projetos","projects");tab("Agentes","agents");
  }
  void tab(String s,String dest){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(screen.equals(dest)?cyan:fg);b.setBackground(shape(card));nav.addView(b,new LinearLayout.LayoutParams(0,d(45),1));b.setOnClickListener(v->leave(()->show(dest)));}
  void home(){LinearLayout hero=panel("NEXUS COMMAND CENTER","Uma oficina de projetos para o seu Android.");TextView txt=label("Crie jogos. Programe. Evolua.",21,true);txt.setTextColor(cyan);hero.addView(txt);button(hero,"Criar projeto",()->create());panel("Game Studio","Crie a estrutura de jogos Godot com scripts e arquivos editáveis.");panel("Code Studio","Crie projetos Python e web, edite código e exporte arquivos.");panel("NEXUS IA local","Nesta versão Android, o editor funciona offline. Modelos de IA pesados e execução do NEXUS SWARM dependem do aplicativo desktop e de uma integração futura.");}
  File projectDir() throws Exception {if(!current.matches("[a-zA-Z0-9_ -]{1,60}"))throw new Exception("Nome inválido");return new File(base,current);}
  File target(String relative) throws Exception {File root=projectDir().getCanonicalFile();File file=new File(root,relative).getCanonicalFile();if(!file.toPath().startsWith(root.toPath())||file.equals(root))throw new Exception("Caminho inválido");return file;}
  void write(String path,String data) throws Exception {File f=target(path);f.getParentFile().mkdirs();try(FileOutputStream o=new FileOutputStream(f)){o.write(data.getBytes(StandardCharsets.UTF_8));}}
  String read(String path) throws Exception {File f=target(path);if(!f.isFile()||f.length()>1000000)throw new Exception("Arquivo não disponível ou muito grande");try(FileInputStream in=new FileInputStream(f)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return out.toString("UTF-8");}}
  void create(){LinearLayout c=column();c.setPadding(d(22),d(12),d(22),d(8));EditText nm=input(c,"Nome do projeto","MeuProjeto",false);new AlertDialog.Builder(this).setTitle("Novo projeto").setView(c).setItems(new String[]{"Jogo Godot 2D","Aplicativo Python","Site HTML"},(dialog,which)->{try{String n=nm.getText().toString().trim();if(!n.matches("[a-zA-Z0-9_ -]{1,60}"))throw new Exception("Nome inválido");File dir=new File(base,n);if(!dir.mkdir())throw new Exception("Este projeto já existe");current=n;
    if(which==0){write("project.godot","config_version=5\n[application]\nconfig/name=\""+n+"\"\nrun/main_scene=\"res://scenes/main.tscn\"\n");write("scenes/main.tscn","[gd_scene format=3]\n[node name=\"Main\" type=\"Node2D\"]\n");write("scripts/player.gd","extends CharacterBody2D\n\nfunc _physics_process(delta):\n    pass\n");}
    else if(which==1)write("main.py","def main():\n    print(\"NEXUS IA\")\n\nif __name__ == \"__main__\":\n    main()\n");
    else write("index.html","<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"></head><body><h1>"+n+"</h1></body></html>\n");
    write("README.md","# "+n+"\nProjeto criado na NEXUS IA Mobile.\n");show("project");}catch(Exception e){error(e);}}).setNegativeButton("Cancelar",null).show();}
  void projects(){stack.addView(label("Seus projetos",22,true));button(stack,"+ Novo projeto",()->create());File[] files=base.listFiles();if(files==null||files.length==0){panel("Nenhum projeto ainda","Crie um jogo ou programa para começar.");return;}Arrays.sort(files);for(File f:files)if(f.isDirectory()){LinearLayout p=panel("◈ "+f.getName(),"Toque para abrir e editar.");button(p,"Abrir projeto",()->{current=f.getName();show("project");});}}
  void visit(File dir,String prefix){File[] files=dir.listFiles();if(files==null)return;Arrays.sort(files);for(File f:files){String rel=prefix+f.getName();if(f.isDirectory())visit(f,rel+"/");else{LinearLayout p=panel("▤ "+rel,null);button(p,"Editar arquivo",()->{selected=rel;show("editor");});}}}
  void project(){stack.addView(label("Projeto: "+current,22,true));button(stack,"+ Novo arquivo",()->{EditText e=new EditText(this);e.setHint("ex.: scripts/inventario.gd");new AlertDialog.Builder(this).setTitle("Nome do arquivo").setView(e).setPositiveButton("Criar",(x,y)->{try{String path=e.getText().toString().trim();if(path.isEmpty())throw new Exception("Informe o caminho");write(path,"");selected=path;show("editor");}catch(Exception ex){error(ex);}}).setNegativeButton("Cancelar",null).show();});button(stack,"Exportar projeto ZIP",()->export());try{visit(projectDir(),"");}catch(Exception e){error(e);}}
  void edit(){stack.addView(label(selected,18,true));try{previous=read(selected);}catch(Exception e){error(e);show("project");return;}editor=input(stack,"Código-fonte",previous,true);button(stack,"Salvar arquivo",()->save());button(stack,"Voltar ao projeto",()->leave(()->show("project")));}
  void save(){try{write(selected,editor.getText().toString());previous=editor.getText().toString();Toast.makeText(this,"Salvo",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e);}}
  void leave(Runnable next){if(screen.equals("editor")&&editor!=null&&!editor.getText().toString().equals(previous)){new AlertDialog.Builder(this).setTitle("Alterações não salvas").setMessage("Deseja sair sem salvar?").setNegativeButton("Cancelar",null).setPositiveButton("Sair",(d,w)->next.run()).show();}else next.run();}
  void agents(){LinearLayout p=panel("NEXUS SWARM","Agentes de desenvolvimento");p.addView(label("Os agentes de IA e a compilação de jogos precisam do NEXUS desktop conectado. Este APK não executa modelos grandes no telefone.",14,false));button(p,"Abrir projetos",()->show("projects"));}
  void export(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/zip");i.putExtra(Intent.EXTRA_TITLE,current+".zip");startActivityForResult(i,EXPORT);}
  void zip(ZipOutputStream out,File root,File folder)throws Exception{File[] list=folder.listFiles();if(list==null)return;for(File file:list){if(file.isDirectory())zip(out,root,file);else{String rel=root.toPath().relativize(file.toPath()).toString().replace('\\','/');out.putNextEntry(new ZipEntry(rel));try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}out.closeEntry();}}}
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==EXPORT&&result==RESULT_OK&&data!=null){Uri uri=data.getData();try(OutputStream dest=getContentResolver().openOutputStream(uri);ZipOutputStream zip=new ZipOutputStream(dest)){File p=projectDir();zip(zip,p,p);Toast.makeText(this,"Projeto exportado",Toast.LENGTH_LONG).show();}catch(Exception e){error(e);}}}
  void error(Exception e){new AlertDialog.Builder(this).setTitle("Erro").setMessage(e.getMessage()).setPositiveButton("OK",null).show();}
}
