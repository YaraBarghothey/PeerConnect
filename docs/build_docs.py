# Builds docs/ProjectReport.pdf, docs/TestCases.pdf and docs/Complete_Project_Explanation_AR.pdf (WeasyPrint).
# PeerConnect - YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2
import os, sys
from weasyprint import HTML
here = os.path.dirname(os.path.abspath(__file__))
def build(src, out): 
    HTML(filename=os.path.join(here, 'src', src), base_url=os.path.join(here, 'src') + '/').write_pdf(os.path.join(here, out))
    print('built', out)
if __name__ == '__main__':
    for a in sys.argv[1:] or ['report']:
        if a == 'report': build('report.html', 'ProjectReport.pdf')
        if a == 'tests': build('testcases.html', 'TestCases.pdf')
        if a == 'arabic':
            import markdown
            md = open(os.path.join(here, 'Complete_Project_Explanation_AR.md'), encoding='utf-8').read()
            body = markdown.markdown(md, extensions=['tables', 'fenced_code', 'sane_lists'])
            css = open(os.path.join(here, 'src', 'arabic.css'), encoding='utf-8').read()
            html = '<!DOCTYPE html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><style>' + css + '</style></head><body>' + body + '</body></html>'
            HTML(string=html, base_url=here + '/').write_pdf(os.path.join(here, 'Complete_Project_Explanation_AR.pdf'))
            print('built Complete_Project_Explanation_AR.pdf')
