using System;
using System.Windows.Markup;

namespace KankiVPN
{
    /// <summary>XAML text: {k:T 'English text'} shows its translation.
    /// The window is rebuilt when the language changes.</summary>
    [MarkupExtensionReturnType(typeof(string))]
    public sealed class T : MarkupExtension
    {
        public T(string text) { Text = text; }

        [ConstructorArgument("text")]
        public string Text { get; set; }

        public override object ProvideValue(IServiceProvider serviceProvider) => Strings.T(Text);
    }
}
