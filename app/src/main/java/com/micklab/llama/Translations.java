package com.micklab.llama;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * Machine-generated UI translations for the languages beyond ja/en, keyed by the English
 * string passed to {@code localizedText(ja, en)}. Value array order: fr, es, pt, de, it, zh, ko.
 * A missing key (or language) falls back to English, so the app is always usable even for
 * strings not yet in the table (e.g. concatenated / multi-line messages, or the manual).
 */
public final class Translations {
    private Translations() {
    }

    private static final Map<String, String[]> T = new HashMap<>();

    // en, then: fr, es, pt, de, it, zh, ko
    private static void e(String en, String fr, String es, String pt, String de, String it, String zh, String ko) {
        T.put(en, new String[]{fr, es, pt, de, it, zh, ko});
    }

    static {
        registerAll();
    }

    public static String get(Context ctx, String ja, String en) {
        String lang = AppLanguageManager.getOrInitDisplayLanguage(ctx);
        if (AppLanguageManager.LANGUAGE_JA.equals(lang)) {
            return ja;
        }
        if (AppLanguageManager.LANGUAGE_EN.equals(lang)) {
            return en;
        }
        String[] a = T.get(en);
        if (a != null) {
            int i = -1;
            switch (lang) {
                case "fr": i = 0; break;
                case "es": i = 1; break;
                case "pt": i = 2; break;
                case "de": i = 3; break;
                case "it": i = 4; break;
                case "zh": i = 5; break;
                case "ko": i = 6; break;
                default: break;
            }
            if (i >= 0 && a[i] != null) {
                return a[i];
            }
        }
        return en; // fallback to English
    }

    /** Quick Start dialog body, single language (display language, else English). */
    public static String quickStart(Context ctx) {
        switch (AppLanguageManager.getOrInitDisplayLanguage(ctx)) {
            case "ja":
                return "【重要】モデルのダウンロードには数GB単位の通信が必要になる場合があります。モバイルデータ通信を使用すると高額な通信料が発生する可能性があるため、可能な限りWi-Fi環境でのダウンロードを強く推奨します。\n\n" +
                        "0) 起動時にAPI/WebUI有効化ポップアップが表示された場合は、必要に応じて有効化するか、「次回以降は表示しない」をチェックすると次回から表示されません。\n" +
                        "1) SettingsでモデルをLoad Modelしてください。\n2) SAVE & CLOSEでメイン画面へ戻ります。\n3) 入力フィールドに指示文を入れてSendすると、回答が表示されます。\n\n" +
                        "【ヒント】大きなモデルのロードは、アドレス空間の確保失敗またはユーザ操作により中断される場合があります。その場合は次回起動時に通知を表示します。必要に応じて、より小さいモデルを試すか、Settings から再度 Load Model を実行してください。Re-init Model は実行中でも押せて、進行中の生成を停止して現在のプロファイルを再初期化します。失敗した場合はログを確認するか、Settings から再度 Load Model を実行してください。";
            case "fr":
                return "IMPORTANT : le téléchargement des modèles peut nécessiter plusieurs gigaoctets de données. L'utilisation des données mobiles peut entraîner des frais importants ; le téléchargement en Wi-Fi est fortement recommandé.\n\n" +
                        "0) Si la fenêtre d'activation de l'API/WebUI apparaît au démarrage, activez-la si nécessaire ou cochez « Ne plus afficher » pour l'ignorer par la suite.\n" +
                        "1) Dans les Paramètres, chargez un modèle avec Load Model.\n2) Appuyez sur SAVE & CLOSE pour revenir à l'écran principal.\n3) Saisissez votre instruction dans le champ et appuyez sur Send pour afficher la réponse.\n\n" +
                        "CONSEILS : le chargement d'un très grand modèle peut s'arrêter si la réservation d'espace d'adressage échoue ou si le processus est interrompu par l'utilisateur. Dans ce cas, l'application affiche un avis au prochain démarrage. Si nécessaire, essayez un modèle plus petit ou rechargez le modèle depuis les Paramètres. Re-init Model est disponible pendant l'exécution et arrête la génération en cours avant de réinitialiser le profil actuel. En cas d'échec, consultez le journal ou rechargez le modèle depuis les Paramètres.";
            case "es":
                return "IMPORTANTE: descargar modelos puede requerir gigabytes de datos. Usar datos móviles puede generar cargos importantes; se recomienda encarecidamente descargar por Wi-Fi.\n\n" +
                        "0) Si aparece la ventana de activación de API/WebUI al iniciar, actívela si es necesario o marque «No mostrar la próxima vez» para omitirla en el futuro.\n" +
                        "1) En Configuración, cargue un modelo con Load Model.\n2) Toque SAVE & CLOSE para volver a la pantalla principal.\n3) Escriba su instrucción en el campo y toque Send para mostrar la respuesta.\n\n" +
                        "CONSEJOS: la carga de un modelo muy grande puede detenerse si falla la reserva de espacio de direcciones o si el proceso se interrumpe por acción del usuario. En ese caso, la app muestra un aviso en el siguiente inicio. Si es necesario, pruebe un modelo más pequeño o vuelva a cargar el modelo desde Configuración. Re-init Model está disponible mientras hay trabajo en curso y detiene la generación activa antes de reinicializar el perfil actual. Si falla, revise el registro o cargue el modelo de nuevo desde Configuración.";
            case "pt":
                return "IMPORTANTE: baixar modelos pode exigir gigabytes de dados. Usar dados móveis pode gerar cobranças significativas; é altamente recomendável baixar via Wi-Fi.\n\n" +
                        "0) Se a janela de ativação da API/WebUI aparecer ao iniciar, ative-a quando necessário ou marque \"Não mostrar da próxima vez\" para ignorá-la depois.\n" +
                        "1) Em Configurações, carregue um modelo com Load Model.\n2) Toque em SAVE & CLOSE para voltar à tela principal.\n3) Digite sua instrução no campo e toque em Send para exibir a resposta.\n\n" +
                        "DICAS: carregar um modelo muito grande pode parar se a reserva de espaço de endereço falhar ou se o processo for interrompido pelo usuário. Nesse caso, o app mostra um aviso no próximo início. Se necessário, tente um modelo menor ou carregue o modelo novamente em Configurações. Re-init Model está disponível durante a execução e interrompe a geração ativa antes de reinicializar o perfil atual. Se falhar, verifique o registro ou carregue o modelo novamente em Configurações.";
            case "de":
                return "WICHTIG: Das Herunterladen von Modellen kann mehrere Gigabyte an Daten erfordern. Die Nutzung mobiler Daten kann hohe Kosten verursachen; das Herunterladen über WLAN wird dringend empfohlen.\n\n" +
                        "0) Wenn beim Start das API/WebUI-Aktivierungsfenster erscheint, aktivieren Sie es bei Bedarf oder aktivieren Sie „Nächstes Mal nicht anzeigen“, um es künftig zu überspringen.\n" +
                        "1) Laden Sie in den Einstellungen ein Modell mit Load Model.\n2) Tippen Sie auf SAVE & CLOSE, um zum Hauptbildschirm zurückzukehren.\n3) Geben Sie Ihre Anweisung in das Feld ein und tippen Sie auf Send, um die Antwort anzuzeigen.\n\n" +
                        "TIPPS: Das Laden eines sehr großen Modells kann abbrechen, wenn die Reservierung des Adressraums fehlschlägt oder der Vorgang durch den Benutzer unterbrochen wird. In diesem Fall zeigt die App beim nächsten Start einen Hinweis. Versuchen Sie bei Bedarf ein kleineres Modell oder laden Sie das Modell erneut über die Einstellungen. Re-init Model ist während der Ausführung verfügbar und stoppt die laufende Generierung, bevor das aktuelle Profil neu initialisiert wird. Bei Fehlern prüfen Sie das Protokoll oder laden Sie das Modell erneut über die Einstellungen.";
            case "it":
                return "IMPORTANTE: il download dei modelli può richiedere gigabyte di dati. L'uso della rete mobile può comportare costi elevati; si consiglia vivamente di scaricare tramite Wi-Fi.\n\n" +
                        "0) Se all'avvio compare la finestra di attivazione API/WebUI, attivala se necessario o seleziona \"Non mostrare la prossima volta\" per ignorarla in futuro.\n" +
                        "1) In Impostazioni, carica un modello con Load Model.\n2) Tocca SAVE & CLOSE per tornare alla schermata principale.\n3) Inserisci la tua istruzione nel campo e tocca Send per mostrare la risposta.\n\n" +
                        "SUGGERIMENTI: il caricamento di un modello molto grande può interrompersi se la riserva dello spazio di indirizzamento fallisce o se il processo viene interrotto dall'utente. In tal caso l'app mostra un avviso al successivo avvio. Se necessario, prova un modello più piccolo o ricarica il modello dalle Impostazioni. Re-init Model è disponibile durante l'esecuzione e arresta la generazione attiva prima di reinizializzare il profilo corrente. Se fallisce, controlla il log o ricarica il modello dalle Impostazioni.";
            case "zh":
                return "【重要】下载模型可能需要数 GB 的流量。使用移动数据可能产生高额费用，强烈建议在 Wi-Fi 环境下下载。\n\n" +
                        "0) 若启动时出现 API/WebUI 启用弹窗，可按需启用，或勾选“下次不再显示”以后不再提示。\n" +
                        "1) 在“设置”中用 Load Model 加载模型。\n2) 点按 SAVE & CLOSE 返回主界面。\n3) 在输入框中输入指令并点按 Send 显示回复。\n\n" +
                        "【提示】加载超大模型可能因地址空间预留失败或被用户操作中断而停止。此时应用会在下次启动时显示通知。如有需要，请尝试更小的模型或在“设置”中重新加载模型。Re-init Model 在运行中也可点按，会停止进行中的生成并重新初始化当前配置文件。若失败，请查看日志或在“设置”中重新加载模型。";
            case "ko":
                return "【중요】모델 다운로드에는 수 GB의 데이터가 필요할 수 있습니다. 모바일 데이터를 사용하면 높은 요금이 발생할 수 있으므로 가능하면 Wi-Fi에서 다운로드하는 것을 강력히 권장합니다.\n\n" +
                        "0) 시작 시 API/WebUI 활성화 팝업이 표시되면 필요에 따라 활성화하거나 \"다음에 표시 안 함\"을 선택하면 이후에는 표시되지 않습니다.\n" +
                        "1) 설정에서 Load Model로 모델을 로드하세요.\n2) SAVE & CLOSE를 눌러 메인 화면으로 돌아갑니다.\n3) 입력란에 지시문을 입력하고 Send를 누르면 응답이 표시됩니다.\n\n" +
                        "【팁】매우 큰 모델의 로드는 주소 공간 예약 실패 또는 사용자 조작으로 중단될 수 있습니다. 이 경우 앱이 다음 실행 시 알림을 표시합니다. 필요하면 더 작은 모델을 시도하거나 설정에서 모델을 다시 로드하세요. Re-init Model은 실행 중에도 누를 수 있으며 진행 중인 생성을 멈추고 현재 프로필을 다시 초기화합니다. 실패하면 로그를 확인하거나 설정에서 모델을 다시 로드하세요.";
            default:
                return "IMPORTANT: Downloading models may require gigabytes of data. Using mobile/cellular data may incur significant charges; downloading over Wi-Fi is strongly recommended.\n\n" +
                        "0) If the API/WebUI enablement popup appears at launch, enable it when needed or check \"Don't show next time\" to skip it on future launches.\n" +
                        "1) In Settings, load a model with Load Model.\n2) Tap SAVE & CLOSE to return to the main screen.\n3) Enter your instruction in the input field and tap Send to display the response.\n\n" +
                        "TIPS: Loading a very large model may stop because address-space reservation fails or because the process was interrupted by user action. In that case the app shows a notice on the next launch. If needed, try a smaller model or load the model again from Settings. Re-init Model is available while work is running and stops the active generation before reinitializing the current profile. If it fails, check the log or load the model again from Settings.";
        }
    }

    private static void registerAll() {
        e("  [Recommended]", "  [Recommandé]", "  [Recomendado]", "  [Recomendado]", "  [Empfohlen]", "  [Consigliato]", "  [推荐]", "  [권장]");
        e(" (applied on next prompt)", " (appliqué à la prochaine invite)", " (se aplica en el próximo prompt)", " (aplicado no próximo prompt)", " (wird bei der nächsten Eingabe angewendet)", " (applicato al prossimo prompt)", "（下次提示时应用）", " (다음 프롬프트에 적용)");
        e("A file with that name already exists", "Un fichier portant ce nom existe déjà", "Ya existe un archivo con ese nombre", "Já existe um arquivo com esse nome", "Eine Datei mit diesem Namen existiert bereits", "Esiste già un file con quel nome", "已存在同名文件", "같은 이름의 파일이 이미 있습니다");
        e("API/WebUI: Stopped", "API/WebUI : Arrêté", "API/WebUI: Detenido", "API/WebUI: Parado", "API/WebUI: Gestoppt", "API/WebUI: Arrestato", "API/WebUI：已停止", "API/WebUI: 중지됨");
        e("Available", "Disponible", "Disponible", "Disponível", "Verfügbar", "Disponibile", "可用", "사용 가능");
        e("Back", "Retour", "Atrás", "Voltar", "Zurück", "Indietro", "返回", "뒤로");
        e("Cancel", "Annuler", "Cancelar", "Cancelar", "Abbrechen", "Annulla", "取消", "취소");
        e("Cannot change the profile while a model operation is running", "Impossible de changer de profil pendant une opération sur le modèle", "No se puede cambiar el perfil mientras se ejecuta una operación del modelo", "Não é possível alterar o perfil enquanto uma operação do modelo está em execução", "Profil kann während einer laufenden Modelloperation nicht geändert werden", "Impossibile cambiare profilo mentre è in corso un'operazione sul modello", "模型操作运行时无法更改配置文件", "모델 작업이 실행 중일 때는 프로필을 변경할 수 없습니다");
        e("Cannot open browser: ", "Impossible d'ouvrir le navigateur : ", "No se puede abrir el navegador: ", "Não é possível abrir o navegador: ", "Browser kann nicht geöffnet werden: ", "Impossibile aprire il browser: ", "无法打开浏览器：", "브라우저를 열 수 없습니다: ");
        e("Cannot rename the currently loaded model", "Impossible de renommer le modèle actuellement chargé", "No se puede renombrar el modelo cargado actualmente", "Não é possível renomear o modelo carregado atualmente", "Das aktuell geladene Modell kann nicht umbenannt werden", "Impossibile rinominare il modello attualmente caricato", "无法重命名当前已加载的模型", "현재 로드된 모델의 이름을 변경할 수 없습니다");
        e("Cannot rename the currently loaded model. Switch models first", "Impossible de renommer le modèle actuellement chargé. Changez d'abord de modèle", "No se puede renombrar el modelo cargado actualmente. Cambie de modelo primero", "Não é possível renomear o modelo carregado atualmente. Troque de modelo primeiro", "Das aktuell geladene Modell kann nicht umbenannt werden. Wechseln Sie zuerst das Modell", "Impossibile rinominare il modello attualmente caricato. Cambia prima modello", "无法重命名当前已加载的模型。请先切换模型", "현재 로드된 모델의 이름을 변경할 수 없습니다. 먼저 모델을 전환하세요");
        e("Cannot rename while a model operation is running", "Impossible de renommer pendant une opération sur le modèle", "No se puede renombrar mientras se ejecuta una operación del modelo", "Não é possível renomear enquanto uma operação do modelo está em execução", "Umbenennen während einer laufenden Modelloperation nicht möglich", "Impossibile rinominare mentre è in corso un'operazione sul modello", "模型操作运行时无法重命名", "모델 작업이 실행 중일 때는 이름을 변경할 수 없습니다");
        e("Clear Log", "Effacer le journal", "Borrar registro", "Limpar registro", "Protokoll löschen", "Cancella log", "清除日志", "로그 지우기");
        e("Clear", "Effacer", "Borrar", "Limpar", "Löschen", "Cancella", "清除", "지우기");
        e("Clipboard is unavailable", "Le presse-papiers est indisponible", "El portapapeles no está disponible", "A área de transferência está indisponível", "Zwischenablage ist nicht verfügbar", "Gli appunti non sono disponibili", "剪贴板不可用", "클립보드를 사용할 수 없습니다");
        e("Close", "Fermer", "Cerrar", "Fechar", "Schließen", "Chiudi", "关闭", "닫기");
        e("Connect to Wi-Fi to show the LAN URL.", "Connectez-vous au Wi-Fi pour afficher l'URL LAN.", "Conéctese a Wi-Fi para mostrar la URL de LAN.", "Conecte-se ao Wi-Fi para mostrar a URL da LAN.", "Mit WLAN verbinden, um die LAN-URL anzuzeigen.", "Connettiti al Wi-Fi per mostrare l'URL LAN.", "连接 Wi-Fi 以显示 LAN URL。", "LAN URL을 표시하려면 Wi-Fi에 연결하세요.");
        e("Copied: ", "Copié : ", "Copiado: ", "Copiado: ", "Kopiert: ", "Copiato: ", "已复制：", "복사됨: ");
        e("Copy", "Copier", "Copiar", "Copiar", "Kopieren", "Copia", "复制", "복사");
        e("Could not determine the selected file name", "Impossible de déterminer le nom du fichier sélectionné", "No se pudo determinar el nombre del archivo seleccionado", "Não foi possível determinar o nome do arquivo selecionado", "Der Name der ausgewählten Datei konnte nicht ermittelt werden", "Impossibile determinare il nome del file selezionato", "无法确定所选文件名", "선택한 파일 이름을 확인할 수 없습니다");
        e("Could not open the file picker", "Impossible d'ouvrir le sélecteur de fichiers", "No se pudo abrir el selector de archivos", "Não foi possível abrir o seletor de arquivos", "Dateiauswahl konnte nicht geöffnet werden", "Impossibile aprire il selettore di file", "无法打开文件选择器", "파일 선택기를 열 수 없습니다");
        e("Could not open the selected file", "Impossible d'ouvrir le fichier sélectionné", "No se pudo abrir el archivo seleccionado", "Não foi possível abrir o arquivo selecionado", "Die ausgewählte Datei konnte nicht geöffnet werden", "Impossibile aprire il file selezionato", "无法打开所选文件", "선택한 파일을 열 수 없습니다");
        e("Delete", "Supprimer", "Eliminar", "Excluir", "Löschen", "Elimina", "删除", "삭제");
        e("Display Language", "Langue d'affichage", "Idioma de la interfaz", "Idioma de exibição", "Anzeigesprache", "Lingua di visualizzazione", "显示语言", "표시 언어");
        e("Display language updated", "Langue d'affichage mise à jour", "Idioma de la interfaz actualizado", "Idioma de exibição atualizado", "Anzeigesprache aktualisiert", "Lingua di visualizzazione aggiornata", "显示语言已更新", "표시 언어가 업데이트되었습니다");
        e("Documents", "Documents", "Documentos", "Documentos", "Dokumente", "Documenti", "文档", "문서");
        e("Don't show next time", "Ne plus afficher", "No mostrar la próxima vez", "Não mostrar da próxima vez", "Nächstes Mal nicht anzeigen", "Non mostrare la prossima volta", "下次不再显示", "다음에 표시 안 함");
        e("Download and load", "Télécharger et charger", "Descargar y cargar", "Baixar e carregar", "Herunterladen und laden", "Scarica e carica", "下载并加载", "다운로드 후 로드");
        e("Download completed", "Téléchargement terminé", "Descarga completada", "Download concluído", "Download abgeschlossen", "Download completato", "下载完成", "다운로드 완료");
        e("Download failed", "Échec du téléchargement", "Error en la descarga", "Falha no download", "Download fehlgeschlagen", "Download non riuscito", "下载失败", "다운로드 실패");
        e("Download mmproj", "Télécharger mmproj", "Descargar mmproj", "Baixar mmproj", "mmproj herunterladen", "Scarica mmproj", "下载 mmproj", "mmproj 다운로드");
        e("Download only (Recommended)", "Télécharger seulement (Recommandé)", "Solo descargar (Recomendado)", "Somente baixar (Recomendado)", "Nur herunterladen (Empfohlen)", "Solo download (Consigliato)", "仅下载（推荐）", "다운로드만 (권장)");
        e("Download only", "Télécharger seulement", "Solo descargar", "Somente baixar", "Nur herunterladen", "Solo download", "仅下载", "다운로드만");
        e("Download", "Télécharger", "Descargar", "Baixar", "Herunterladen", "Scarica", "下载", "다운로드");
        e("Enable API/WebUI", "Activer l'API/WebUI", "Habilitar API/WebUI", "Ativar API/WebUI", "API/WebUI aktivieren", "Abilita API/WebUI", "启用 API/WebUI", "API/WebUI 활성화");
        e("Enable", "Activer", "Habilitar", "Ativar", "Aktivieren", "Abilita", "启用", "활성화");
        e("Enter Prompt:", "Saisir l'invite :", "Ingrese el prompt:", "Digite o prompt:", "Eingabe:", "Inserisci il prompt:", "输入提示：", "프롬프트 입력:");
        e("Enter a file name", "Saisissez un nom de fichier", "Ingrese un nombre de archivo", "Digite um nome de arquivo", "Dateinamen eingeben", "Inserisci un nome file", "输入文件名", "파일 이름을 입력하세요");
        e("Enter a keyword", "Saisissez un mot-clé", "Ingrese una palabra clave", "Digite uma palavra-chave", "Stichwort eingeben", "Inserisci una parola chiave", "输入关键词", "키워드를 입력하세요");
        e("Enter a port number between 1 and 65535", "Saisissez un numéro de port entre 1 et 65535", "Ingrese un número de puerto entre 1 y 65535", "Digite um número de porta entre 1 e 65535", "Portnummer zwischen 1 und 65535 eingeben", "Inserisci un numero di porta tra 1 e 65535", "输入 1 到 65535 之间的端口号", "1에서 65535 사이의 포트 번호를 입력하세요");
        e("Enter a valid port to show the LAN URL", "Saisissez un port valide pour afficher l'URL LAN", "Ingrese un puerto válido para mostrar la URL de LAN", "Digite uma porta válida para mostrar a URL da LAN", "Gültigen Port eingeben, um die LAN-URL anzuzeigen", "Inserisci una porta valida per mostrare l'URL LAN", "输入有效端口以显示 LAN URL", "LAN URL을 표시하려면 유효한 포트를 입력하세요");
        e("Enter a valid port to show the server URL", "Saisissez un port valide pour afficher l'URL du serveur", "Ingrese un puerto válido para mostrar la URL del servidor", "Digite uma porta válida para mostrar a URL do servidor", "Gültigen Port eingeben, um die Server-URL anzuzeigen", "Inserisci una porta valida per mostrare l'URL del server", "输入有效端口以显示服务器 URL", "서버 URL을 표시하려면 유효한 포트를 입력하세요");
        e("Enter your prompt here", "Saisissez votre invite ici", "Escriba su prompt aquí", "Digite seu prompt aqui", "Geben Sie hier Ihre Eingabe ein", "Inserisci qui il tuo prompt", "在此输入您的提示", "여기에 프롬프트를 입력하세요");
        e("Failed to load GGUF files: ", "Échec du chargement des fichiers GGUF : ", "Error al cargar los archivos GGUF: ", "Falha ao carregar os arquivos GGUF: ", "GGUF-Dateien konnten nicht geladen werden: ", "Impossibile caricare i file GGUF: ", "加载 GGUF 文件失败：", "GGUF 파일 로드 실패: ");
        e("Failed to load profile: ", "Échec du chargement du profil : ", "Error al cargar el perfil: ", "Falha ao carregar o perfil: ", "Profil konnte nicht geladen werden: ", "Impossibile caricare il profilo: ", "加载配置文件失败：", "프로필 로드 실패: ");
        e("Failed to rename: ", "Échec du renommage : ", "Error al renombrar: ", "Falha ao renomear: ", "Umbenennen fehlgeschlagen: ", "Rinomina non riuscita: ", "重命名失败：", "이름 변경 실패: ");
        e("Failed to update profile: ", "Échec de la mise à jour du profil : ", "Error al actualizar el perfil: ", "Falha ao atualizar o perfil: ", "Profil konnte nicht aktualisiert werden: ", "Impossibile aggiornare il profilo: ", "更新配置文件失败：", "프로필 업데이트 실패: ");
        e("File name cannot contain a slash", "Le nom de fichier ne peut pas contenir de barre oblique", "El nombre de archivo no puede contener una barra", "O nome do arquivo não pode conter uma barra", "Der Dateiname darf keinen Schrägstrich enthalten", "Il nome del file non può contenere una barra", "文件名不能包含斜杠", "파일 이름에 슬래시를 포함할 수 없습니다");
        e("Function Definitions JSON must be a JSON array whose items include a name", "Le JSON des définitions de fonctions doit être un tableau JSON dont les éléments incluent un nom", "El JSON de definiciones de funciones debe ser un arreglo JSON cuyos elementos incluyan un nombre", "O JSON de definições de funções deve ser um array JSON cujos itens incluam um nome", "Das JSON der Funktionsdefinitionen muss ein JSON-Array sein, dessen Elemente einen Namen enthalten", "Il JSON delle definizioni di funzione deve essere un array JSON i cui elementi includono un nome", "函数定义 JSON 必须是其项包含 name 的 JSON 数组", "함수 정의 JSON은 항목에 name이 포함된 JSON 배열이어야 합니다");
        e("Hugging Face search failed: ", "Échec de la recherche Hugging Face : ", "Error en la búsqueda de Hugging Face: ", "Falha na busca do Hugging Face: ", "Hugging-Face-Suche fehlgeschlagen: ", "Ricerca Hugging Face non riuscita: ", "Hugging Face 搜索失败：", "Hugging Face 검색 실패: ");
        e("Hugging Face search is already running", "La recherche Hugging Face est déjà en cours", "La búsqueda de Hugging Face ya está en ejecución", "A busca do Hugging Face já está em execução", "Die Hugging-Face-Suche läuft bereits", "La ricerca Hugging Face è già in corso", "Hugging Face 搜索已在进行中", "Hugging Face 검색이 이미 실행 중입니다");
        e("Interrupted Model Load", "Chargement du modèle interrompu", "Carga del modelo interrumpida", "Carregamento do modelo interrompido", "Modellladen unterbrochen", "Caricamento del modello interrotto", "模型加载被中断", "모델 로드 중단됨");
        e("LAN URL", "URL LAN", "URL de LAN", "URL da LAN", "LAN-URL", "URL LAN", "LAN URL", "LAN URL");
        e("Likes ", "J'aime ", "Me gusta ", "Curtidas ", "Likes ", "Mi piace ", "点赞 ", "좋아요 ");
        e("Local URL", "URL locale", "URL local", "URL local", "Lokale URL", "URL locale", "本地 URL", "로컬 URL");
        e("Logs cleared", "Journaux effacés", "Registros borrados", "Registros limpos", "Protokolle gelöscht", "Log cancellati", "日志已清除", "로그가 지워졌습니다");
        e("MCP config JSON must be a JSON array", "Le JSON de configuration MCP doit être un tableau JSON", "El JSON de configuración de MCP debe ser un arreglo JSON", "O JSON de configuração do MCP deve ser um array JSON", "Das MCP-Konfigurations-JSON muss ein JSON-Array sein", "Il JSON di configurazione MCP deve essere un array JSON", "MCP 配置 JSON 必须是 JSON 数组", "MCP 구성 JSON은 JSON 배열이어야 합니다");
        e("MTP draft model", "Modèle de brouillon MTP", "Modelo de borrador MTP", "Modelo de rascunho MTP", "MTP-Entwurfsmodell", "Modello draft MTP", "MTP 草稿模型", "MTP 드래프트 모델");
        e("MTP: using own head (save config to apply)", "MTP : utilisation de sa propre tête (enregistrez la config pour appliquer)", "MTP: usando su propia cabeza (guarde la configuración para aplicar)", "MTP: usando a própria cabeça (salve a configuração para aplicar)", "MTP: eigener Kopf wird verwendet (Konfiguration speichern zum Anwenden)", "MTP: uso della propria testa (salva la configurazione per applicare)", "MTP：使用自身的头（保存配置以应用）", "MTP: 자체 헤드 사용 (적용하려면 설정 저장)");
        e("Model Maintenance", "Maintenance du modèle", "Mantenimiento del modelo", "Manutenção do modelo", "Modellwartung", "Manutenzione del modello", "模型维护", "모델 유지 관리");
        e("Model Output:", "Sortie du modèle :", "Salida del modelo:", "Saída do modelo:", "Modellausgabe:", "Output del modello:", "模型输出：", "모델 출력:");
        e("Model import failed", "Échec de l'importation du modèle", "Error al importar el modelo", "Falha ao importar o modelo", "Modellimport fehlgeschlagen", "Importazione del modello non riuscita", "模型导入失败", "모델 가져오기 실패");
        e("Model import is already running", "L'importation du modèle est déjà en cours", "La importación del modelo ya está en ejecución", "A importação do modelo já está em execução", "Modellimport läuft bereits", "L'importazione del modello è già in corso", "模型导入已在进行中", "모델 가져오기가 이미 실행 중입니다");
        e("Model name or keyword (partial match)", "Nom du modèle ou mot-clé (correspondance partielle)", "Nombre del modelo o palabra clave (coincidencia parcial)", "Nome do modelo ou palavra-chave (correspondência parcial)", "Modellname oder Stichwort (Teiltreffer)", "Nome del modello o parola chiave (corrispondenza parziale)", "模型名称或关键词（部分匹配）", "모델 이름 또는 키워드 (부분 일치)");
        e("Name must end with .gguf", "Le nom doit se terminer par .gguf", "El nombre debe terminar en .gguf", "O nome deve terminar com .gguf", "Der Name muss mit .gguf enden", "Il nome deve terminare con .gguf", "名称必须以 .gguf 结尾", "이름은 .gguf로 끝나야 합니다");
        e("No downloadable GGUF files were found", "Aucun fichier GGUF téléchargeable n'a été trouvé", "No se encontraron archivos GGUF descargables", "Nenhum arquivo GGUF para download foi encontrado", "Keine herunterladbaren GGUF-Dateien gefunden", "Nessun file GGUF scaricabile trovato", "未找到可下载的 GGUF 文件", "다운로드 가능한 GGUF 파일을 찾을 수 없습니다");
        e("No downloaded model files found", "Aucun fichier de modèle téléchargé trouvé", "No se encontraron archivos de modelo descargados", "Nenhum arquivo de modelo baixado encontrado", "Keine heruntergeladenen Modelldateien gefunden", "Nessun file di modello scaricato trovato", "未找到已下载的模型文件", "다운로드된 모델 파일을 찾을 수 없습니다");
        e("No matching GGUF repositories were found", "Aucun dépôt GGUF correspondant n'a été trouvé", "No se encontraron repositorios GGUF coincidentes", "Nenhum repositório GGUF correspondente foi encontrado", "Keine passenden GGUF-Repositorys gefunden", "Nessun repository GGUF corrispondente trovato", "未找到匹配的 GGUF 仓库", "일치하는 GGUF 저장소를 찾을 수 없습니다");
        e("No stored GGUF files were found", "Aucun fichier GGUF stocké n'a été trouvé", "No se encontraron archivos GGUF almacenados", "Nenhum arquivo GGUF armazenado foi encontrado", "Keine gespeicherten GGUF-Dateien gefunden", "Nessun file GGUF memorizzato trovato", "未找到已保存的 GGUF 文件", "저장된 GGUF 파일을 찾을 수 없습니다");
        e("Not now", "Pas maintenant", "Ahora no", "Agora não", "Nicht jetzt", "Non ora", "暂不", "나중에");
        e("Output will appear here", "La sortie apparaîtra ici", "La salida aparecerá aquí", "A saída aparecerá aqui", "Die Ausgabe erscheint hier", "L'output apparirà qui", "输出将显示在此处", "출력이 여기에 표시됩니다");
        e("Please select a .gguf file", "Veuillez sélectionner un fichier .gguf", "Seleccione un archivo .gguf", "Selecione um arquivo .gguf", "Bitte eine .gguf-Datei auswählen", "Seleziona un file .gguf", "请选择一个 .gguf 文件", ".gguf 파일을 선택하세요");
        e("Privacy Policy", "Politique de confidentialité", "Política de privacidad", "Política de privacidade", "Datenschutzrichtlinie", "Informativa sulla privacy", "隐私政策", "개인정보 처리방침");
        e("Profile selected: ", "Profil sélectionné : ", "Perfil seleccionado: ", "Perfil selecionado: ", "Profil ausgewählt: ", "Profilo selezionato: ", "已选择配置文件：", "선택된 프로필: ");
        e("Projector: not selected", "Projecteur : non sélectionné", "Proyector: no seleccionado", "Projetor: não selecionado", "Projektor: nicht ausgewählt", "Proiettore: non selezionato", "投影器：未选择", "프로젝터: 선택 안 됨");
        e("Quick Start", "Démarrage rapide", "Inicio rápido", "Início rápido", "Schnellstart", "Avvio rapido", "快速入门", "빠른 시작");
        e("Rename file", "Renommer le fichier", "Renombrar archivo", "Renomear arquivo", "Datei umbenennen", "Rinomina file", "重命名文件", "파일 이름 변경");
        e("Rename", "Renommer", "Renombrar", "Renomear", "Umbenennen", "Rinomina", "重命名", "이름 변경");
        e("Renamed to: ", "Renommé en : ", "Renombrado a: ", "Renomeado para: ", "Umbenannt in: ", "Rinominato in: ", "已重命名为：", "이름 변경됨: ");
        e("Reset", "Réinitialiser", "Restablecer", "Redefinir", "Zurücksetzen", "Reimposta", "重置", "재설정");
        e("Search GGUF on Hugging Face", "Rechercher des GGUF sur Hugging Face", "Buscar GGUF en Hugging Face", "Buscar GGUF no Hugging Face", "GGUF auf Hugging Face suchen", "Cerca GGUF su Hugging Face", "在 Hugging Face 上搜索 GGUF", "Hugging Face에서 GGUF 검색");
        e("Search", "Rechercher", "Buscar", "Buscar", "Suchen", "Cerca", "搜索", "검색");
        e("Select a GGUF file", "Sélectionner un fichier GGUF", "Seleccionar un archivo GGUF", "Selecionar um arquivo GGUF", "GGUF-Datei auswählen", "Seleziona un file GGUF", "选择一个 GGUF 文件", "GGUF 파일 선택");
        e("Select a model first", "Sélectionnez d'abord un modèle", "Seleccione un modelo primero", "Selecione um modelo primeiro", "Zuerst ein Modell auswählen", "Seleziona prima un modello", "请先选择一个模型", "먼저 모델을 선택하세요");
        e("Select a repository", "Sélectionner un dépôt", "Seleccionar un repositorio", "Selecionar um repositório", "Repository auswählen", "Seleziona un repository", "选择一个仓库", "저장소 선택");
        e("Select document:", "Sélectionner un document :", "Seleccionar documento:", "Selecionar documento:", "Dokument auswählen:", "Seleziona documento:", "选择文档：", "문서 선택:");
        e("Select mmproj", "Sélectionner mmproj", "Seleccionar mmproj", "Selecionar mmproj", "mmproj auswählen", "Seleziona mmproj", "选择 mmproj", "mmproj 선택");
        e("Select", "Sélectionner", "Seleccionar", "Selecionar", "Auswählen", "Seleziona", "选择", "선택");
        e("Send", "Envoyer", "Enviar", "Enviar", "Senden", "Invia", "发送", "전송");
        e("Separate draft: ", "Brouillon séparé : ", "Borrador separado: ", "Rascunho separado: ", "Separater Entwurf: ", "Draft separato: ", "独立草稿：", "별도 드래프트: ");
        e("Set anyway", "Définir quand même", "Establecer de todos modos", "Definir mesmo assim", "Trotzdem festlegen", "Imposta comunque", "仍然设置", "그래도 설정");
        e("Settings", "Paramètres", "Configuración", "Configurações", "Einstellungen", "Impostazioni", "设置", "설정");
        e("Show Status", "Afficher l'état", "Mostrar estado", "Mostrar status", "Status anzeigen", "Mostra stato", "显示状态", "상태 표시");
        e("Skip", "Ignorer", "Omitir", "Pular", "Überspringen", "Salta", "跳过", "건너뛰기");
        e("Start API/WebUI", "Démarrer l'API/WebUI", "Iniciar API/WebUI", "Iniciar API/WebUI", "API/WebUI starten", "Avvia API/WebUI", "启动 API/WebUI", "API/WebUI 시작");
        e("Starting...\n", "Démarrage...\n", "Iniciando...\n", "Iniciando...\n", "Wird gestartet...\n", "Avvio...\n", "正在启动...\n", "시작 중...\n");
        e("Stop API/WebUI", "Arrêter l'API/WebUI", "Detener API/WebUI", "Parar API/WebUI", "API/WebUI stoppen", "Arresta API/WebUI", "停止 API/WebUI", "API/WebUI 중지");
        e("The currently loaded model cannot be replaced. Switch models or delete it first", "Le modèle actuellement chargé ne peut pas être remplacé. Changez de modèle ou supprimez-le d'abord", "El modelo cargado actualmente no se puede reemplazar. Cambie de modelo o elimínelo primero", "O modelo carregado atualmente não pode ser substituído. Troque de modelo ou exclua-o primeiro", "Das aktuell geladene Modell kann nicht ersetzt werden. Wechseln Sie zuerst das Modell oder löschen Sie es", "Il modello attualmente caricato non può essere sostituito. Cambia modello o eliminalo prima", "无法替换当前已加载的模型。请先切换或删除它", "현재 로드된 모델은 교체할 수 없습니다. 먼저 모델을 전환하거나 삭제하세요");
        e("The previous model load record could not be read. If needed, load the model again from Settings.", "L'enregistrement du chargement de modèle précédent n'a pas pu être lu. Si nécessaire, rechargez le modèle depuis les Paramètres.", "No se pudo leer el registro de carga del modelo anterior. Si es necesario, vuelva a cargar el modelo desde Configuración.", "Não foi possível ler o registro de carregamento do modelo anterior. Se necessário, carregue o modelo novamente em Configurações.", "Der vorherige Modellladevorgang konnte nicht gelesen werden. Laden Sie das Modell bei Bedarf erneut über die Einstellungen.", "Impossibile leggere il record di caricamento del modello precedente. Se necessario, ricarica il modello dalle Impostazioni.", "无法读取上一次的模型加载记录。如有需要，请从“设置”重新加载模型。", "이전 모델 로드 기록을 읽을 수 없습니다. 필요하면 설정에서 모델을 다시 로드하세요.");
        e("The selected model file is unavailable", "Le fichier de modèle sélectionné est indisponible", "El archivo de modelo seleccionado no está disponible", "O arquivo de modelo selecionado está indisponível", "Die ausgewählte Modelldatei ist nicht verfügbar", "Il file del modello selezionato non è disponibile", "所选模型文件不可用", "선택한 모델 파일을 사용할 수 없습니다");
        e("This GGUF looks like an mmproj / projector. Download-only is recommended. Load it immediately after the download?", "Ce GGUF ressemble à un mmproj / projecteur. Le téléchargement seul est recommandé. Le charger immédiatement après le téléchargement ?", "Este GGUF parece un mmproj / proyector. Se recomienda solo descargar. ¿Cargarlo inmediatamente después de la descarga?", "Este GGUF parece um mmproj / projetor. Recomenda-se somente baixar. Carregá-lo imediatamente após o download?", "Diese GGUF sieht aus wie ein mmproj / Projektor. Nur Herunterladen wird empfohlen. Direkt nach dem Download laden?", "Questo GGUF sembra un mmproj / proiettore. Si consiglia solo il download. Caricarlo subito dopo il download?", "此 GGUF 看起来像 mmproj / 投影器。建议仅下载。下载后立即加载吗？", "이 GGUF는 mmproj / 프로젝터로 보입니다. 다운로드만 권장합니다. 다운로드 후 바로 로드할까요?");
        e("This will download the model and an available projector. Load the model immediately after the download?", "Ceci téléchargera le modèle et un projecteur disponible. Charger le modèle immédiatement après le téléchargement ?", "Esto descargará el modelo y un proyector disponible. ¿Cargar el modelo inmediatamente después de la descarga?", "Isto baixará o modelo e um projetor disponível. Carregar o modelo imediatamente após o download?", "Dadurch werden das Modell und ein verfügbarer Projektor heruntergeladen. Modell direkt nach dem Download laden?", "Questo scaricherà il modello e un proiettore disponibile. Caricare il modello subito dopo il download?", "这将下载模型和一个可用的投影器。下载后立即加载模型吗？", "모델과 사용 가능한 프로젝터를 다운로드합니다. 다운로드 후 바로 모델을 로드할까요?");
        e("This will download the model from the web. Load it immediately after the download?", "Ceci téléchargera le modèle depuis le web. Le charger immédiatement après le téléchargement ?", "Esto descargará el modelo de la web. ¿Cargarlo inmediatamente después de la descarga?", "Isto baixará o modelo da web. Carregá-lo imediatamente após o download?", "Dadurch wird das Modell aus dem Web heruntergeladen. Direkt nach dem Download laden?", "Questo scaricherà il modello dal web. Caricarlo subito dopo il download?", "这将从网络下载模型。下载后立即加载吗？", "웹에서 모델을 다운로드합니다. 다운로드 후 바로 로드할까요?");
        e("Unknown", "Inconnu", "Desconocido", "Desconhecido", "Unbekannt", "Sconosciuto", "未知", "알 수 없음");
        e("Update", "Mettre à jour", "Actualizar", "Atualizar", "Aktualisieren", "Aggiorna", "更新", "업데이트");
        e("Use as model", "Utiliser comme modèle", "Usar como modelo", "Usar como modelo", "Als Modell verwenden", "Usa come modello", "用作模型", "모델로 사용");
        e("Use this model's own MTP head (recommended)", "Utiliser la propre tête MTP de ce modèle (recommandé)", "Usar la propia cabeza MTP de este modelo (recomendado)", "Usar a própria cabeça MTP deste modelo (recomendado)", "Eigenen MTP-Kopf dieses Modells verwenden (empfohlen)", "Usa la testa MTP propria di questo modello (consigliato)", "使用此模型自身的 MTP 头（推荐）", "이 모델 자체의 MTP 헤드 사용 (권장)");
        e("User Manual", "Manuel d'utilisation", "Manual de usuario", "Manual do usuário", "Benutzerhandbuch", "Manuale utente", "用户手册", "사용 설명서");
        e("View Log", "Afficher le journal", "Ver registro", "Ver registro", "Protokoll anzeigen", "Visualizza log", "查看日志", "로그 보기");
        e("Web UI (tap to open / long-press to copy): ", "Web UI (appuyez pour ouvrir / appui long pour copier) : ", "Web UI (toque para abrir / mantenga pulsado para copiar): ", "Web UI (toque para abrir / pressione e segure para copiar): ", "Web UI (tippen zum Öffnen / lange drücken zum Kopieren): ", "Web UI (tocca per aprire / tieni premuto per copiare): ", "Web UI（点按打开 / 长按复制）：", "Web UI (탭하여 열기 / 길게 눌러 복사): ");
        e("in", "entrée", "entrada", "entrada", "Eingabe", "input", "输入", "입력");
        e("mmproj may be incompatible", "mmproj peut être incompatible", "mmproj puede ser incompatible", "mmproj pode ser incompatível", "mmproj ist möglicherweise inkompatibel", "mmproj potrebbe essere incompatibile", "mmproj 可能不兼容", "mmproj가 호환되지 않을 수 있습니다");
        e("out", "sortie", "salida", "saída", "Ausgabe", "output", "输出", "출력");
        e("temp", "temp", "temp", "temp", "Temp", "temp", "温度", "온도");
        e("time", "temps", "tiempo", "tempo", "Zeit", "tempo", "时间", "시간");
        e("▶ Direct Run (tap to expand)", "▶ Exécution directe (appuyez pour développer)", "▶ Ejecución directa (toque para expandir)", "▶ Execução direta (toque para expandir)", "▶ Direktausführung (zum Aufklappen tippen)", "▶ Esecuzione diretta (tocca per espandere)", "▶ 直接运行（点按展开）", "▶ 직접 실행 (탭하여 펼치기)");
        e("▶ Processing Status/Logs", "▶ État du traitement / Journaux", "▶ Estado del procesamiento/Registros", "▶ Status do processamento/Registros", "▶ Verarbeitungsstatus/Protokolle", "▶ Stato elaborazione/Log", "▶ 处理状态/日志", "▶ 처리 상태/로그");
        e("▼ Direct Run", "▼ Exécution directe", "▼ Ejecución directa", "▼ Execução direta", "▼ Direktausführung", "▼ Esecuzione diretta", "▼ 直接运行", "▼ 직접 실행");
        e("▼ Processing Status/Logs", "▼ État du traitement / Journaux", "▼ Estado del procesamiento/Registros", "▼ Status do processamento/Registros", "▼ Verarbeitungsstatus/Protokolle", "▼ Stato elaborazione/Log", "▼ 处理状态/日志", "▼ 처리 상태/로그");

        // Fixed parts split out of runtime-concatenated messages (batch #2).
        e("Current profile now uses: ", "Le profil actuel utilise désormais : ", "El perfil actual ahora usa: ", "O perfil atual agora usa: ", "Aktuelles Profil verwendet jetzt: ", "Il profilo attuale ora usa: ", "当前配置文件现在使用：", "현재 프로필이 다음을 사용합니다: ");
        e("Updated current profile: ", "Profil actuel mis à jour : ", "Perfil actual actualizado: ", "Perfil atual atualizado: ", "Aktuelles Profil aktualisiert: ", "Profilo attuale aggiornato: ", "已更新当前配置文件：", "현재 프로필 업데이트됨: ");
        e("Searching Hugging Face... ", "Recherche sur Hugging Face... ", "Buscando en Hugging Face... ", "Pesquisando no Hugging Face... ", "Suche auf Hugging Face... ", "Ricerca su Hugging Face... ", "正在搜索 Hugging Face... ", "Hugging Face 검색 중... ");
        e("Loading GGUF files... ", "Chargement des fichiers GGUF... ", "Cargando archivos GGUF... ", "Carregando arquivos GGUF... ", "GGUF-Dateien werden geladen... ", "Caricamento dei file GGUF... ", "正在加载 GGUF 文件... ", "GGUF 파일 로드 중... ");
        e("Selected model: ", "Modèle sélectionné : ", "Modelo seleccionado: ", "Modelo selecionado: ", "Ausgewähltes Modell: ", "Modello selezionato: ", "已选择模型：", "선택한 모델: ");
        e("Importing model... ", "Importation du modèle... ", "Importando modelo... ", "Importando modelo... ", "Modell wird importiert... ", "Importazione del modello... ", "正在导入模型... ", "모델 가져오는 중... ");
        e("Imported mmproj: ", "mmproj importé : ", "mmproj importado: ", "mmproj importado: ", "mmproj importiert: ", "mmproj importato: ", "已导入 mmproj：", "mmproj 가져옴: ");
        e("Imported model file: ", "Fichier de modèle importé : ", "Archivo de modelo importado: ", "Arquivo de modelo importado: ", "Modelldatei importiert: ", "File del modello importato: ", "已导入模型文件：", "모델 파일 가져옴: ");
        e("The selected mmproj is incompatible and was disabled; loaded text-only: ", "Le mmproj sélectionné est incompatible et a été désactivé ; chargé en texte seul : ", "El mmproj seleccionado es incompatible y se desactivó; cargado solo texto: ", "O mmproj selecionado é incompatível e foi desativado; carregado somente texto: ", "Das ausgewählte mmproj ist inkompatibel und wurde deaktiviert; nur Text geladen: ", "Il mmproj selezionato è incompatibile ed è stato disattivato; caricato solo testo: ", "所选 mmproj 不兼容，已禁用；已仅加载文本：", "선택한 mmproj가 호환되지 않아 비활성화되었습니다. 텍스트 전용으로 로드됨: ");
        e("Download complete: ", "Téléchargement terminé : ", "Descarga completada: ", "Download concluído: ", "Download abgeschlossen: ", "Download completato: ", "下载完成：", "다운로드 완료: ");
        e("Projector: ", "Projecteur : ", "Proyector: ", "Projetor: ", "Projektor: ", "Proiettore: ", "投影器：", "프로젝터: ");
        e(" (save config to apply)", " (enregistrez la config pour appliquer)", " (guarde la configuración para aplicar)", " (salve a configuração para aplicar)", " (Konfiguration speichern zum Anwenden)", " (salva la configurazione per applicare)", "（保存配置以应用）", " (적용하려면 설정 저장)");
        e("The selected mmproj (", "Le mmproj sélectionné (", "El mmproj seleccionado (", "O mmproj selecionado (", "Das ausgewählte mmproj (", "Il mmproj selezionato (", "所选 mmproj (", "선택한 mmproj (");
        e(") may be incompatible with this model. If it is, it will be disabled automatically at load time. Set it anyway?", ") peut être incompatible avec ce modèle. Si c'est le cas, il sera désactivé automatiquement au chargement. Le définir quand même ?", ") puede ser incompatible con este modelo. Si lo es, se desactivará automáticamente al cargar. ¿Establecerlo de todos modos?", ") pode ser incompatível com este modelo. Se for, será desativado automaticamente ao carregar. Definir mesmo assim?", ") ist möglicherweise mit diesem Modell inkompatibel. In diesem Fall wird es beim Laden automatisch deaktiviert. Trotzdem festlegen?", ") potrebbe essere incompatibile con questo modello. In tal caso, verrà disattivato automaticamente al caricamento. Impostarlo comunque?", ") 可能与此模型不兼容。若不兼容，将在加载时自动禁用。仍然设置吗？", ")가 이 모델과 호환되지 않을 수 있습니다. 그럴 경우 로드 시 자동으로 비활성화됩니다. 그래도 설정할까요?");
        e("This multimodal model also uses an mmproj (", "Ce modèle multimodal utilise aussi un mmproj (", "Este modelo multimodal también usa un mmproj (", "Este modelo multimodal também usa um mmproj (", "Dieses multimodale Modell verwendet auch ein mmproj (", "Questo modello multimodale usa anche un mmproj (", "此多模态模型还使用 mmproj (", "이 멀티모달 모델은 mmproj도 사용합니다 (");
        e("). Download it as well?\nIf you skip, the model loads text-only.", "). Le télécharger aussi ?\nSi vous ignorez, le modèle se charge en texte seul.", "). ¿Descargarlo también?\nSi lo omite, el modelo se carga solo texto.", "). Baixá-lo também?\nSe você pular, o modelo carrega somente texto.", "). Ebenfalls herunterladen?\nWenn Sie überspringen, wird das Modell nur als Text geladen.", "). Scaricarlo anche?\nSe salti, il modello si carica solo testo.", ")。也一并下载吗？\n若跳过，模型将仅加载文本。", ")도 다운로드할까요?\n건너뛰면 모델이 텍스트 전용으로 로드됩니다.");
        e("Download projector", "Télécharger le projecteur", "Descargar proyector", "Baixar projetor", "Projektor herunterladen", "Scarica proiettore", "下载投影器", "프로젝터 다운로드");
        e("Download model", "Télécharger le modèle", "Descargar modelo", "Baixar modelo", "Modell herunterladen", "Scarica modello", "下载模型", "모델 다운로드");
        e("Hide Log", "Masquer le journal", "Ocultar registro", "Ocultar registro", "Protokoll ausblenden", "Nascondi log", "隐藏日志", "로그 숨기기");
        e("Enable the local API/WebUI server?\n\nIf enabled, the API and WebUI can be used from this device or the same local network. The WebUI is available in a browser at http://<device-ip>:", "Activer le serveur API/WebUI local ?\n\nS'il est activé, l'API et le WebUI peuvent être utilisés depuis cet appareil ou le même réseau local. Le WebUI est accessible dans un navigateur à l'adresse http://<device-ip>:", "¿Habilitar el servidor local de API/WebUI?\n\nSi se habilita, la API y el WebUI se pueden usar desde este dispositivo o la misma red local. El WebUI está disponible en un navegador en http://<device-ip>:", "Ativar o servidor local de API/WebUI?\n\nSe ativado, a API e o WebUI podem ser usados a partir deste dispositivo ou da mesma rede local. O WebUI está disponível em um navegador em http://<device-ip>:", "Lokalen API/WebUI-Server aktivieren?\n\nWenn aktiviert, können API und WebUI von diesem Gerät oder demselben lokalen Netzwerk aus verwendet werden. Das WebUI ist in einem Browser unter http://<device-ip>:", "Attivare il server API/WebUI locale?\n\nSe attivato, l'API e il WebUI possono essere usati da questo dispositivo o dalla stessa rete locale. Il WebUI è disponibile in un browser all'indirizzo http://<device-ip>:", "启用本地 API/WebUI 服务器吗？\n\n启用后，可从本机或同一局域网使用 API 和 WebUI。WebUI 可在浏览器中通过 http://<device-ip>:", "로컬 API/WebUI 서버를 활성화할까요?\n\n활성화하면 이 기기나 동일한 로컬 네트워크에서 API와 WebUI를 사용할 수 있습니다. WebUI는 브라우저에서 http://<device-ip>:");
        e("/ . You can also change this later from the main screen.", "/ . Vous pouvez aussi le modifier plus tard depuis l'écran principal.", "/ . También puede cambiar esto más tarde desde la pantalla principal.", "/ . Você também pode alterar isso mais tarde na tela principal.", "/ erreichbar. Sie können dies später auch über den Hauptbildschirm ändern.", "/ . Puoi anche modificarlo in seguito dalla schermata principale.", "/ 打开。之后也可在主界面切换。", "/ 에서 열 수 있습니다. 나중에 메인 화면에서도 변경할 수 있습니다.");
        e("The previous model load was interrupted because address-space reservation failed or because the process was interrupted by user action.\n\nProfile: ", "Le chargement précédent du modèle a été interrompu car la réservation de l'espace d'adressage a échoué ou parce que le processus a été interrompu par l'utilisateur.\n\nProfil : ", "La carga anterior del modelo se interrumpió porque falló la reserva de espacio de direcciones o porque el proceso fue interrumpido por una acción del usuario.\n\nPerfil: ", "O carregamento anterior do modelo foi interrompido porque a reserva de espaço de endereço falhou ou porque o processo foi interrompido por ação do usuário.\n\nPerfil: ", "Das vorherige Laden des Modells wurde abgebrochen, weil die Reservierung des Adressraums fehlschlug oder weil der Vorgang durch den Benutzer unterbrochen wurde.\n\nProfil: ", "Il caricamento precedente del modello è stato interrotto perché la riserva dello spazio di indirizzamento è fallita o perché il processo è stato interrotto dall'utente.\n\nProfilo: ", "上一次模型加载因地址空间预留失败或被用户操作中断而停止。\n\n配置文件：", "이전 모델 로드가 주소 공간 예약 실패 또는 사용자 조작으로 인해 중단되었습니다.\n\n프로필: ");
        e("\nModel: ", "\nModèle : ", "\nModelo: ", "\nModelo: ", "\nModell: ", "\nModello: ", "\n模型：", "\n모델: ");
        e("\n\nNo automatic retry was performed. If needed, load the model again from Settings.", "\n\nAucune nouvelle tentative automatique n'a été effectuée. Si nécessaire, rechargez le modèle depuis les Paramètres.", "\n\nNo se realizó ningún reintento automático. Si es necesario, vuelva a cargar el modelo desde Configuración.", "\n\nNenhuma nova tentativa automática foi realizada. Se necessário, carregue o modelo novamente em Configurações.", "\n\nEs wurde kein automatischer erneuter Versuch durchgeführt. Laden Sie das Modell bei Bedarf erneut über die Einstellungen.", "\n\nNon è stato eseguito alcun nuovo tentativo automatico. Se necessario, ricarica il modello dalle Impostazioni.", "\n\n未自动重试。如有需要，请从“设置”重新加载模型。", "\n\n자동 재시도는 수행되지 않았습니다. 필요하면 설정에서 모델을 다시 로드하세요.");
        e("API/WebUI: Running on port ", "API/WebUI : en cours d'exécution sur le port ", "API/WebUI: en ejecución en el puerto ", "API/WebUI: em execução na porta ", "API/WebUI: läuft auf Port ", "API/WebUI: in esecuzione sulla porta ", "API/WebUI：正在端口 ", "API/WebUI: 포트 ");
        e(" (WebUI: /)", " (WebUI : /)", " (WebUI: /)", " (WebUI: /)", " (WebUI: /)", " (WebUI: /)", " 上运行 (WebUI: /)", "에서 실행 중 (WebUI: /)");
        e(" copied to clipboard", " copié dans le presse-papiers", " copiado al portapapeles", " copiado para a área de transferência", " in die Zwischenablage kopiert", " copiato negli appunti", " 已复制到剪贴板", " 클립보드에 복사됨");
        e(" (updated profiles: ", " (profils mis à jour : ", " (perfiles actualizados: ", " (perfis atualizados: ", " (aktualisierte Profile: ", " (profili aggiornati: ", "（已更新配置文件：", " (업데이트된 프로필: ");
        e(")", ")", ")", ")", ")", ")", "）", ")");
    }
}
